package io.github.filmonm.whatsmyap.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.filmonm.whatsmyap.wlc.HostKeys
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WLC username/password, locked with a PIN.
 *
 * Two layers: the PIN (PBKDF2) encrypts the credentials, and that is wrapped again with a key that lives in the
 * tablet's hardware keystore and never leaves it - so the stored file is useless off this tablet, and on it
 * still needs the PIN. A wrong PIN fails locally and never contacts a WLC.
 */
class Creds(context: Context) {

    private val prefs = context.getSharedPreferences("wlc_creds", Context.MODE_PRIVATE)

    companion object {
        private const val ALIAS = "whatsmyap_wlc_creds"
        private const val ITER = 120_000
    }

    fun exists() = prefs.contains("blob")

    fun reset() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun pinKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITER, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** Slow on purpose (PIN stretching) - call off the main thread. */
    fun save(user: String, pw: String, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val inner = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, pinKey(pin, salt)) }
        val innerCt = inner.doFinal(JSONObject().put("user", user).put("pw", pw).toString().toByteArray())
        val packed = JSONObject().put("salt", b64(salt)).put("iv", b64(inner.iv)).put("ct", b64(innerCt)).toString().toByteArray()
        val outer = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, deviceKey()) }
        val blob = outer.doFinal(packed)
        prefs.edit().putString("blob", b64(blob)).putString("iv", b64(outer.iv)).apply()
    }

    /** (user, pw), or null if the PIN is wrong. Never touches the network. */
    fun load(pin: String): Pair<String, String>? {
        val outer = Cipher.getInstance("AES/GCM/NoPadding")
        outer.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, unb64(prefs.getString("iv", "")!!)))
        val packed = JSONObject(String(outer.doFinal(unb64(prefs.getString("blob", "")!!))))
        val inner = Cipher.getInstance("AES/GCM/NoPadding")
        inner.init(Cipher.DECRYPT_MODE, pinKey(pin, unb64(packed.getString("salt"))), GCMParameterSpec(128, unb64(packed.getString("iv"))))
        return try {
            val c = JSONObject(String(inner.doFinal(unb64(packed.getString("ct")))))
            c.getString("user") to c.getString("pw")
        } catch (e: AEADBadTagException) {
            null
        }
    }
}

/** What the app remembers about controllers: recent IPs, SSH host keys, most APs seen, subnet -> site. */
class WlcMemory(context: Context) : HostKeys {

    private val prefs = context.getSharedPreferences("wlc_memory", Context.MODE_PRIVATE)

    // ---- recent WLC IPs (most recent first)
    val recentHosts: List<String> get() = prefs.getString("hosts", "")!!.split(',').filter { it.isNotBlank() }
    fun remember(host: String) {
        prefs.edit().putString("hosts", (listOf(host) + recentHosts.filter { it != host }).take(6).joinToString(",")).apply()
    }

    // ---- SSH host keys
    override fun get(host: String): String? = prefs.getString("key_$host", null)
    override fun put(host: String, fingerprint: String) = prefs.edit().putString("key_$host", fingerprint).apply()
    fun forgetKey(host: String) = prefs.edit().remove("key_$host").apply()

    // ---- Cisco lists only joined APs: remember the most ever seen per controller
    fun apBaseline(wlc: String, joined: Int, reset: Boolean): Int {
        val best = prefs.getInt("apmax_$wlc", 0)
        if (reset || joined > best) {
            prefs.edit().putInt("apmax_$wlc", joined).apply()
            return joined
        }
        return best
    }

    // ---- subnet -> site, learned on WLC visits so later Field Survey rows know the school
    private fun subnet24(ip: String?) = ip?.split('.')?.takeIf { it.size == 4 }?.take(3)?.joinToString(".")?.let { "$it.0/24" }

    fun learnSite(ip: String?, site: String, siteType: String) {
        val net = subnet24(ip) ?: return
        if (site.isEmpty()) return
        val all = JSONObject(prefs.getString("sites", "{}")!!)
        val v = JSONObject().put("site", site).put("site_type", siteType)
        if (all.optJSONObject(net)?.toString() == v.toString()) return
        all.put(net, v)
        prefs.edit().putString("sites", all.toString()).apply()
    }

    /** (site, site_type) for a subnet seen before, else null. */
    fun site(ip: String?): Pair<String, String>? {
        val net = subnet24(ip) ?: return null
        val hit = JSONObject(prefs.getString("sites", "{}")!!).optJSONObject(net) ?: return null
        return hit.optString("site") to hit.optString("site_type")
    }
}
