package io.github.filmonm.whatsmyap.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.filmonm.whatsmyap.core.Labels
import kotlinx.coroutines.tasks.await
import java.io.File

/** On-device label reading with Google ML Kit (replaces Tesseract). */
object LabelReader {

    val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    data class Result(val number: Int, val text: String, val ms: Long, val candidates: List<Int>)

    /** Best AP label in some recognized text; `expected` only breaks ties between several readings. */
    fun pick(text: String, expected: Int? = null): Labels.Reading? {
        val found = Labels.parse(text)
        if (found.isEmpty()) return null
        return found.groupBy { it.number }
            .entries.sortedWith(compareBy({ it.key != expected }, { -it.value.size }))
            .first().value.first()
    }

    /** Read the label on a saved photo (EXIF rotation is handled by ML Kit). Null = unreadable. */
    suspend fun readFile(context: Context, file: File, expected: Int? = null): Result? = read(context, file, expected).first

    /** (label or null, everything the reader saw - logged so a miss can be diagnosed). */
    suspend fun read(context: Context, file: File, expected: Int? = null): Pair<Result?, String> {
        val t0 = System.currentTimeMillis()
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val text = recognizer.process(image).await()
        // line by line first (keeps "AP" and its number together), then the whole text
        val lines = text.textBlocks.flatMap { b -> b.lines.map { it.text } }
        val seen = lines.joinToString(" | ").take(200)
        val best = pick(lines.joinToString("\n"), expected) ?: pick(text.text, expected) ?: return null to seen
        val all = (Labels.parse(lines.joinToString("\n")) + Labels.parse(text.text)).map { it.number }.distinct().sorted()
        return Result(best.number, best.raw, System.currentTimeMillis() - t0, all) to seen
    }
}
