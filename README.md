# ELEMANTRA – Android app (for Codemagic)

A native Android project that runs your game **unchanged**. The original HTML file is in
`app/src/main/assets/www/index.html` (your game, plus the online multiplayer described below). The app hosts it in a
full-screen WebView, so every screen, animation, sound and 3D scene is rendered by the same
Chromium engine as in a browser.

## Build it on Codemagic

1. Create a **private** Git repo (GitHub, GitLab or Bitbucket) and push the *contents* of this
   folder to it (`codemagic.yaml` must be at the repo root). The HTML is ~31 MB, which is fine for Git.
2. In Codemagic: **Add application** -> pick the repo -> choose **codemagic.yaml** as the configuration.
3. Start workflow **android-test-apk**. When it finishes, download `app-debug.apk` from the
   build artifacts and install it on a phone. Needs no signing setup.
4. For Google Play use **android-release**:
   - Codemagic > Teams > Code signing identities > Android keystores: upload (or generate) a keystore and
     set its **Reference name** to `elemantra_keystore` (or edit that name in `codemagic.yaml`).
   - Run the workflow; it produces a signed `.apk` and a `.aab` (upload the `.aab` to Play).

## Firebase

The app is set up for the Firebase project app **ELEMANTRA**, package name `com.elemantra.game`
(`applicationId` in `app/build.gradle.kts` matches it).

1. Firebase console > Project settings > your Android app > download **google-services.json**.
2. Put it at `app/google-services.json` and push it to the repo.
3. Build. Firebase Analytics turns on automatically once that file exists; without it the app builds
   and runs normally, just without Firebase.

For features that check your app's signature (Google Sign-In, App Check / Play Integrity) add the
SHA-1 and SHA-256 of your **release keystore** under "Add fingerprint" in Firebase. Codemagic shows
them on the keystore page. Test (debug) builds are signed with a different key each time, so do not use them for that.

## How it works

| Piece | What it does |
|---|---|
| `assets/www/index.html` | Your game, with the Firebase online multiplayer added (see **Online multiplayer**). |
| `MainActivity.kt` | Full-screen WebView, landscape, immersive mode, keeps the screen on, serves the HTML from the APK. |
| `tools/vendor_web_deps.py` | Runs on Codemagic before the build. Downloads three.js, the Tailwind runtime and Google Fonts (Cinzel/Inter) that your page loads from CDNs into `assets/vendor`, so the game also runs **offline**. If a download fails, the app falls back to the CDN when online (`--strict` in the release workflow makes the build fail instead). |
| `tools/ensure_gradle_wrapper.sh` | Generates `gradlew` on the build machine. |

Native behaviour: locked to landscape, immersive full screen, screen stays awake, music autoplay
allowed, system font size ignored (so text matches your design), back button needs a double press to exit,
camera-notch area is letterboxed the way a browser does it.

## Customising

- **Package name / version:** `app/build.gradle.kts` (`applicationId`, `versionName`). `versionCode` comes from
  the Codemagic build number automatically.
- **Update the game:** replace `app/src/main/assets/www/index.html` and push.
- **Icon:** `app/src/main/res/mipmap-*` and `drawable-nodpi` (generated from your loading-screen art).
  `store/play_store_icon_512.png` is ready for the Play listing.
- **Target API:** `targetSdk = 35`. If Google Play asks for a newer level, raise `compileSdk`/`targetSdk`
  (and the Android Gradle Plugin version in the root `build.gradle.kts`).

## Troubleshooting

- Debug builds allow WebView debugging: connect the phone and open `chrome://inspect` on a PC.
- `adb logcat -s Elemantra` shows page console output and whether offline libraries were bundled
  ("Offline libraries bundled: N").
- If the `instance_type: linux_x2` line is rejected, delete it (Codemagic then uses its default machine).
- The page itself needs a phone with an up-to-date **Android System WebView** (Android 7+; auto-updated by Google Play).


## Telling players a new version exists (Firebase)

Every time the app opens, it checks a public, read-only path (`app/version`) in the same Firebase
Realtime Database used for Online Play. If that check shows a newer version than the one baked into
the app, a popup appears — styled like the rest of the game's wood-and-rope UI — that says a new
version is available, with two choices: **Keep playing** (dismiss, keeps playing this version — it
will ask again next time the app opens) and **Get it on WhatsApp**, a button that opens a WhatsApp
chat to **+2349043738558** with a pre-filled message asking for the update. No sign-in is required for
this check, and it does not block or interfere with Online Play.

**To tell players about an update**, after you've shared the new APK/build with them:
1. Firebase console > Realtime Database > Data.
2. Create/edit the path `app/version` with:
   ```json
   { "latest": "1.1.0", "message": "A new version is out with new arenas! Message me on WhatsApp to get it." }
   ```
   - `latest` is required, and must be dotted numbers (e.g. `1.1.0`); `message` is optional — if you
     leave it out, a generic message is shown instead.
3. Any app whose built-in version is older than `latest` will show the popup the next time it's opened.

**To make a build actually report a new version**, open `app/src/main/assets/www/index.html`, search for
`LOCAL_VERSION`, and bump that string (e.g. `'1.0.0'` -> `'1.1.0'`) to match what you put in `latest`
before you build and hand out the new APK. This string is independent of `versionName` in
`app/build.gradle.kts` — it's only ever compared against the Firebase value, never shown on Google Play.

To change the WhatsApp number later, edit `WHATSAPP_NUMBER` right next to `LOCAL_VERSION` in the same file.

## Online multiplayer (Firebase Realtime Database)

Online Play (Create Room / Join Room / Quick Match) is now real. It uses the **web app** config of the Firebase project
`the-elemantra` (inside `index.html`, script `onl-net`) and loads the Firebase SDK from `gstatic.com` the first time the
Online Play window opens, so the rest of the game still runs offline.

**One-time Firebase console setup (required):**
1. Build > Authentication > Sign-in method > enable **Anonymous**.
2. Build > Realtime Database > Rules > paste the contents of `database.rules.json` and Publish.
   (Without Anonymous sign-in the game falls back to no auth, which only works if your rules are open / test mode.)

**How a match works**
- Host creates a room (6-character ID) or Quick Match pairs two players. The guest joins; the host presses Start Match.
- A versus screen shows both fighters (character, element, name), then both arenas start and every round begins at the same server time.
- Each phone simulates only its own fighter. ~20 times a second it sends position, direction, the blended animation clips
  and its health; the other phone renders that ~100-300 ms in the past with interpolation (extrapolation if a packet is late),
  so movement and animations stay smooth. The delay adapts to the measured latency and jitter.
- A hit is detected by the attacker's phone and sent to the victim, whose phone applies it (block, dodge and shield still work)
  and owns its own health. Round / match results and rematch are synchronised; if a player disconnects the other wins.

Data paths: `rooms/CODE`, `pub/CODE` (open public rooms), `live/CODE/h|g` (latest pose), `ev/CODE` (hits, KO, ready, go, effects),
`chat/CODE` (pre-match lobby chat).
Rooms are removed when the host leaves.

**Pre-match chat.** While a room is open (host waiting for a guest, or both sitting in the lobby before
the host presses Start Match), both players see a small chat panel under the player list where they can
type short messages to each other. Messages are stored at `chat/CODE` the same way round events are
(reliable, pushed, auth-gated by `database.rules.json`), are capped to the last 50 per room on read, and
are cleared when the host starts the match or closes/leaves the room. The chat panel is only shown in the
lobby screen — it's not available once a match is in progress.
