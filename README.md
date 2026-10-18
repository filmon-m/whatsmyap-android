# What's My AP (Android)

Proves, AP by AP, that the label on the ceiling matches the AP the controller reports, and that the AP performs to spec.
Native rewrite of the Termux/Python prototype (v2.12).

- **Field Survey** (no login): label photo read on-device by ML Kit, speed test (Cloudflare, Vultr fallbacks),
  Teams check, BSSID/signal/location, survey packaged as zip(s) with an offline report.
- **Network Engineer** (phase 2, unlocked by WLC credentials): Inspect, Debug my AP, Debug my WLC, Verify a survey.

## Get the app on a tablet
Every push to `main` builds a debug APK (GitHub Actions) and publishes it to the
[`dev` release](../../releases/tag/dev). Open that page on the tablet, tap the `.apk`, allow installs from the browser.

## Build locally
Android Studio (Ladybug or newer) - open this folder. Or `./gradlew assembleDebug` with JDK 17 and the Android SDK.

## Layout
| Path | What |
| --- | --- |
| `core/` | Plain-Kotlin rules: label parsing and verdicts, bands, thresholds, Teams check (unit-tested) |
| `data/` | Wi-Fi, location, speed tests, ML Kit label reader, CSV log, survey packaging |
| `ui/` | Compose screens: Home, Field Survey, Camera, Report |
| `assets/dashboard.html` | The prototype's dashboard, reused as the survey report |

Development builds are signed with `app/debug.keystore` (public, development only) so each build installs over the last.
