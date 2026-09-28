# Building GoatTidi

GoatTidi isn't in an app store. You build it from source and install it on your own Android phone. That takes about 30–60 minutes the first time, most of it downloads and one-time Google Cloud setup.

You can follow this guide by hand, or let an AI coding agent (Claude Code, Codex, Cursor, …) do the computer side for you. The agent handles installs, the build, and putting the app on your phone. It stops and tells you exactly what to click for the two parts only you can do: setting up Google Cloud under your own Google account, and turning on USB debugging on your phone.

## Build it with an AI agent

Open your agent in an empty folder and paste:

```text
Build and install the GoatTidi Android app on my phone from source.
Clone https://github.com/WilliamOsman/GoatTidi and follow its BUILDING.md step by step.
Ask me before installing anything on this computer. When a step needs me (Google Cloud
setup, phone settings), stop, give me exact instructions, and wait for me to confirm.
```

---

## Instructions for AI agents

If you are an agent following this file:

- Work through the steps **in order**. Each step ends with a **Check**; don't move on until it passes.
- **Ask before installing software** or changing system settings, and say what you'll install and where. Don't use `sudo` or admin rights without asking.
- Steps marked **👤 You** need the human. Stop, give them the instructions from that step with their details filled in (e.g. their SHA-1), and wait until they confirm.
- Never commit, upload, or print the contents of a keystore file. Never ask for the user's Google password; they sign in on their own screen.
- Don't modify the app's source code unless the user asks you to.
- On Windows use `gradlew.bat` instead of `./gradlew`.

---

## 1. Get the code

```sh
git clone https://github.com/WilliamOsman/GoatTidi.git
cd GoatTidi
```

**Check:** the folder contains `gradlew`.

## 2. Install Java (JDK 17 or newer)

```sh
java -version
```

If that shows version 17 or newer, skip to step 3. Otherwise install JDK 17 (21 also works):

| System | Command |
|---|---|
| macOS (Homebrew) | `brew install --cask temurin@17` |
| Ubuntu / Debian | `sudo apt install openjdk-17-jdk` |
| Fedora | `sudo dnf install java-17-openjdk-devel` |
| Windows | `winget install EclipseAdoptium.Temurin.17.JDK` |

If Android Studio is installed, its bundled JDK works too. Point `JAVA_HOME` at it (on macOS: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

**Check:** `java -version` reports 17 or newer. If several Java versions are installed, make sure `JAVA_HOME` points at a 17+ one.

## 3. Install the Android SDK

If Android Studio is installed, you already have the SDK:

| System | Default SDK location |
|---|---|
| macOS | `~/Library/Android/sdk` |
| Linux | `~/Android/Sdk` |
| Windows | `%LOCALAPPDATA%\Android\Sdk` |

Otherwise install only the command-line tools (no Android Studio needed):

1. Download **"Command line tools only"** for your system from <https://developer.android.com/studio#command-tools>.
2. Unzip it so the tools end up at `<sdk>/cmdline-tools/latest/bin/sdkmanager`, using one of the locations above as `<sdk>`.

Then accept the licenses and install what the app needs:

```sh
<sdk>/cmdline-tools/latest/bin/sdkmanager --licenses
<sdk>/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Tell Gradle where the SDK is. Either set the `ANDROID_HOME` environment variable to `<sdk>`, or create `local.properties` in the project folder (git ignores it):

```properties
sdk.dir=/full/path/to/sdk
```

On Windows, write the path with forward slashes or escaped backslashes (`C:\\Users\\you\\AppData\\Local\\Android\\Sdk`).

**Check:** `<sdk>/platform-tools/adb version` prints a version.

## 4. Build and test

```sh
./gradlew testDebugUnitTest assembleDebug
```

The first run downloads Gradle and the app's libraries and takes several minutes. Later builds take seconds.

**Check:** it ends with `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` exists.

## 5. Get your signing fingerprint

Your first build created a *debug signing key* at `~/.android/debug.keystore` (on Windows: `%USERPROFILE%\.android\debug.keystore`). Google uses its fingerprint to recognise your copy of the app, so it has to be registered in step 6.

```sh
./gradlew :app:signingReport
```

Under `Variant: debug`, copy the `SHA1:` line. It looks like `AB:CD:12:…` with 20 pairs.

**Check:** you have the SHA-1. Agents: show it to the user; they need it in step 6.

> This key belongs to this computer. Builds from another computer get a different key and won't be able to sign in to Drive until you register that one too, or copy `debug.keystore` across. Keep it private and back it up if you plan to rebuild after reinstalling your system. Never commit it.

## 6. 👤 You: set up Google Cloud (one time)

GoatTidi talks to Google Drive directly from your phone, using a Google Cloud project that **you** own. There's no GoatTidi server. This is free.

1. Go to <https://console.cloud.google.com>, signed in with any Google account, and **create a project**. Any name works, e.g. "GoatTidi".
2. **Enable the Drive API:** *APIs & Services → Library*, search **Google Drive API**, click **Enable**.
3. **Set up sign-in:** open **Google Auth Platform** (older console: *APIs & Services → OAuth consent screen*) and click **Get started**:
   - **App name:** GoatTidi (anything). **Support email:** yours.
   - **Audience:** **External** (or *Internal* if you're on a Google Workspace organisation and only use accounts from it).
   - Add your contact email, agree, and create.
4. **Add yourself as a test user:** *Audience → Test users → Add users*. Add the Google account whose Drive you'll back up to. Up to 100 people can be added.
5. **Create the Android client:** *Clients → Create client*:
   - **Application type:** **Android** (not Web or Desktop)
   - **Package name:** `com.goattidi.mediasync`
   - **SHA-1 certificate fingerprint:** the SHA-1 from step 5
   - Click **Create**. There's no client ID or secret to copy: Google recognises the app by its package name and signing key.
6. *Optional:* under **Data access → Add or remove scopes**, add `…/auth/drive.file`, plus `…/auth/drive.readonly` if you'll use the whole-Drive search in Sync Settings. This makes the consent screen list them up front.

Changes can take a few minutes to take effect.

**Check:** you've created the Android client with your package name and SHA-1, and your account is listed as a test user.

## 7. 👤 You: get your phone ready

1. **Turn on Developer options:** *Settings → About phone → Build number*, and tap **Build number** seven times. On Samsung it's *About phone → Software information → Build number*.
2. **Turn on USB debugging:** *Settings → Developer options → USB debugging*.
3. **Connect the phone by USB** and tap **Allow** on the "Allow USB debugging?" prompt. Tick "Always allow from this computer" if you'll update often.

**Check:** `adb devices` lists the phone as `device`. `unauthorized` means the phone prompt hasn't been accepted yet.

Wi-Fi also works: *Developer options → Wireless debugging → Pair device with pairing code*, then `adb pair <ip>:<port>` and `adb connect <ip>:<port>`.

## 8. Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.goattidi.mediasync/.MainActivity
```

If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a GoatTidi already on the phone was signed with a different key. Uninstalling it (`adb uninstall com.goattidi.mediasync`) deletes the app's local sync status and settings, but not your photos or anything on Drive. Agents: **ask the user before uninstalling.**

**Check:** GoatTidi opens on the phone.

## 9. 👤 You: first run

1. Allow access to photos, videos, and audio when asked.
2. Open **⚙ Settings → Connect Google Drive**, pick the test-user account from step 6, and allow access.
3. Settings should now show **✓ Connected as you@…**.

Then select files in the gallery (long-press), tap **Upload**, and watch the queue.

## Updating later

```sh
git pull
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Building on the same computer uses the same key, so the app updates in place and keeps all its data.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Connecting fails with an error mentioning code 10 or "developer console" | The package name or SHA-1 in step 6 doesn't match this build. Re-run `./gradlew :app:signingReport` and compare. New clients can also take a few minutes to take effect. |
| "Drive API is not enabled for this app's Google Cloud project" | Step 6.2 was skipped, or it hasn't taken effect yet (1–2 minutes). |
| Google says access is blocked, or the app isn't verified, for your account | Add that account under *Audience → Test users* (step 6.4). |
| Drive asks you to reconnect every week | Testing-mode apps can have authorizations expire after 7 days. Reconnect in Settings, or publish the consent screen. |
| `SDK location not found` | Set `ANDROID_HOME` or create `local.properties` (step 3). |
| `Unsupported class file major version` or other Java errors | Gradle is using an old Java. Point `JAVA_HOME` at JDK 17+ (step 2). |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| Uploads sit at "waiting" | **Wi-Fi only** is on by default. Connect to Wi-Fi or turn it off in Settings. |

## Artwork

The code is MIT-licensed, but the GoatTidi icon artwork isn't. See [NOTICE](NOTICE).
