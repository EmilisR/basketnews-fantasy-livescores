# EuroLeague Fantasy Live (Android)

Unofficial Android app for **BasketNews EuroLeague Fantasy**. BasketNews only updates
fantasy points after games finish; this app calculates your teams' points **live**
from EuroLeague's own box scores while the games are being played.

> Unofficial, not affiliated with BasketNews or EuroLeague.

It is a Kotlin port of the "EuroLeague Fantasy Live" Chrome extension (same scoring,
name matching, round mapping and polling logic).

## What it does

- Opens `https://fantasy.basketnews.com/` full screen. You log in as usual; cookies are kept.
- When you open one of your teams, the app reads the lineup the site itself loads
  (it never changes the site's requests or responses). It remembers up to 8 recently
  opened EuroLeague teams. LKL and BCL pages are ignored.
- A red **Live points** tile appears next to "Round points" (or a small red badge in
  the top-right corner if the tile can't be found).
- The red **● Live** button opens a native screen: every player with EFF, ±10% adjusted
  points, multiplier and points, plus the scores of the round's games.
- While a game of your teams' round is in progress, a foreground service refreshes
  every ~30 s and shows `"<team>: <points> pts (live)"` in one ongoing notification.
  It stops by itself when nothing is live.

### Scoring (classic / budget mode)
EFF from the live box score, +10% if the player's team is winning, −10% if losing
(while live: whoever leads right now; a tie changes nothing; the percentage is applied
literally, also to negative EFF). Captain ×2 (×3 with a "triple" wildcard), starters and
sixth player ×1, bench ×0.5 (×1 with a "bench" wildcard). A player whose game has not
started has 0. Other game modes (e.g. draft) show "This game mode is not supported yet".

### Data sources
- EuroLeague schedule: `api-live.euroleague.net/v2/competitions/E/seasons/{season}/games`
- Box scores: `live.euroleague.net/api/Boxscore`
- Your lineup: `fantasy.basketnews.com/backend/graphql`, re-read every ~10 minutes with
  the WebView's cookies (if that fails, the last lineup seen in the page is used).

Everything stays on the phone. There is no server.

---

## Getting the APK

All builds run in **GitHub Actions** (`.github/workflows/android.yml`). You don't need
Android Studio or the Android SDK.

### For testing (debug APK)
1. Push to `main` (or open a pull request, or run the workflow by hand:
   **Actions → Android build → Run workflow**).
2. Open the run in the **Actions** tab.
3. Download the **app-debug** artifact at the bottom of the run page. It's a zip that
   contains `app-debug.apk`.

### For friends (signed release on the Releases page)
One-time setup of the signing key is below. Then:
```bash
git tag v0.1.0
git push --tags
```
The workflow builds a signed `app-release.apk` (plus an `.aab` for Google Play) and
publishes them on the repository's **Releases** page, where friends can download the APK
directly. The version name/code come from the tag (`v1.2.3` → versionName `1.2.3`,
versionCode `10203`). You can override them with `-PversionName=… -PversionCode=…`.

### One-time: create the signing key
**Never commit the keystore or its passwords.** (`*.jks` and `*.keystore` are in `.gitignore`.)

a. Create the keystore once, on your own machine (needs a JDK for `keytool`):
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias fantasylive -keyalg RSA -keysize 2048 -validity 10000
   ```
   It asks for a keystore password and a key password (you can use the same one) and
   for your name etc.

b. Base64-encode it:
   ```bash
   base64 -w0 release.jks        # Linux
   base64 -i release.jks         # macOS
   ```

c. In GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add:
   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the base64 text from step b |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `fantasylive` |
   | `KEY_PASSWORD` | the key password |

d. **Back up `release.jks` and both passwords somewhere safe** (password manager, encrypted
   backup). If you lose them, you can never publish an update that installs over the
   existing app: everyone would have to uninstall and reinstall (and on Google Play you'd
   need a key reset).

Without these secrets, debug and pull-request builds still work (the release build is
only run for `v*` tags, and an unsigned release build also configures fine locally).

---

## Installing on a phone (sideloading)

1. Download the APK on the phone (from the Releases page, or unzip the `app-debug`
   artifact and copy `app-debug.apk` to the phone).
2. Open the APK. Android asks to allow installs from that app (browser or Files):
   **Settings → Apps → Special app access → Install unknown apps** → allow it for the
   browser/file manager you used.
3. Tap **Install**. If Play Protect warns about an unknown developer, choose
   **More details → Install anyway**.
4. Open **EuroLeague Fantasy Live**, allow notifications (Android 13+), log in to
   BasketNews with Google and open your team(s).

**Google sign-in:** BasketNews only offers "Sign in with Google", and Google blocks its
login inside embedded WebViews. To make it work, the app presents its WebView as mobile
Chrome (it removes the WebView markers from the user agent and the `X-Requested-With`
header) and keeps the Google login pages inside the app so the session ends up there.
This goes against Google's policy for embedded browsers: Google can detect and block it at
any time, and it would likely be rejected on Google Play.

Debug and release builds are signed with different keys, so to switch between them you
have to uninstall first.

**Battery:** some phones (Samsung, Xiaomi, Huawei…) kill background work aggressively. If
the notification stops updating during games, set the app's battery usage to
**Unrestricted** (Settings → Apps → EuroLeague Fantasy Live → Battery).

---

## Building locally (optional)

Needs JDK 17 and the Android SDK (or Android Studio):
```bash
./gradlew testDebugUnitTest   # scoring/engine unit tests (plain JVM)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```
For a signed release build set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` and run `./gradlew assembleRelease bundleRelease`. Without `KEYSTORE_PATH`
you get an unsigned `app-release-unsigned.apk`.

---

## Publishing to Google Play later

1. Create a Google Play developer account (one-time **$25** fee) at
   <https://play.google.com/console>. New personal accounts must run a closed test with
   testers for a while before they can publish to production.
2. Use the **`.aab`** (Android App Bundle) from a tagged release
   (`app/build/outputs/bundle/release/app-release.aab`, attached to the GitHub Release).
   Play requires an AAB, not an APK. Enable **Play App Signing** and upload with your
   `release.jks` key as the upload key.
3. You need a **privacy policy** URL. Simple version: the app stores your BasketNews
   session cookies and the teams you opened only on your device, talks only to
   fantasy.basketnews.com and EuroLeague's live stats servers, and collects nothing.
   Fill in the **Data safety** form accordingly.
4. Declare the **foreground service** use (type `dataSync`: refreshing live scores during
   games) in the Play Console's app content section.
5. Play requires a recent `targetSdk` (bump `targetSdk`/`compileSdk` in
   `app/build.gradle.kts` to whatever Play currently requires before uploading).
6. Make clear in the listing that the app is unofficial (it uses the BasketNews and
   EuroLeague names only descriptively) — Play rejects apps that look like they
   impersonate a brand.

---

## Project layout

```
app/src/main/java/com/elflive/app/
  core/Scoring.kt      scoring, name matching, lineup → roster, season/round helpers (port of scoring.js)
  core/LineupQuery.kt  the exact GraphQL query (lineupQuery.js)
  core/Engine.kt       polling, round following, caching (background.js)
  data/HttpApi.kt      OkHttp: EuroLeague feeds + BasketNews GraphQL with WebView cookies
  data/DataStoreStore.kt  persisted teams / schedule / final box scores / last state
  MainActivity.kt      WebView, injected script, pushes live points into the page
  TeamsActivity.kt     native players/games screen + About
  LiveService.kt       foreground service + notification
app/src/main/assets/elf.js   page script (sniffer.js + the tile rendering from bridge.js)
app/src/test/...             JUnit tests for the scoring and the engine
```

## Known limitations
- Uses EuroLeague's unofficial live data feed and BasketNews's private GraphQL API;
  either can change without notice.
- The service is started when the app sees a live game (when you open the app or a team).
  It does not wake up on its own for games that start while the app has not been opened.
- On Android 15+, the system limits `dataSync` foreground services to about 6 hours per
  day; the service stops when that limit is reached.
