<img src="GoatTidi_icon.png" alt="GoatTidi app icon: a cartoon goat beside a trash can" width="112" align="right">

# GoatTidi

An Android app for choosing exactly which photos, videos, and audio recordings go to Google Drive — with a per-file sync status you can trust, and a safe way to free up space on your phone afterwards.

Most sync apps mirror whole folders automatically. GoatTidi is the opposite: a gallery of the media on your device where you pick what to upload, see at a glance what's safely on Drive, and clear local copies only once they're provably backed up.

## What "synced" means

A file is marked **✓ Synced** only when Drive reports an MD5 checksum that matches the file on your phone — never just because an upload request succeeded. Everything else follows from that:

- **Free up space** lists only verified-synced files, and re-checks each file's current bytes against Drive immediately before deleting anything. A file edited since upload can't be deleted as "synced".
- **Verify** re-checks everything against Drive and flags files that were changed locally or are gone from Drive.
- Deletion always goes through Android's own confirmation dialog (or an in-app one on Android 8–10). Nothing is ever deleted automatically.

## Features

- Gallery of device photos, videos, and audio with colored status borders (grey not uploaded, blue queued/uploading, green synced, amber changed or missing, red failed); new media shows up on its own while the app is open, and you can pull down to refresh
- Tap-and-hold to select, then upload; filters for type and sync status
- Resumable uploads that survive network drops, app kills, and reboots — they continue from the last confirmed byte, not from zero
- Upload queue with per-file progress, transfer rate, and ETA; Wi-Fi-only and charging-only options
- Destination folder of your choice (default **GoatTidi_<device name>**, e.g. `GoatTidi_Galaxy S23`), laid out flat, by month, or by source folder (Camera, WhatsApp, …)
- Sync detection by checksum: a file whose exact bytes are already on Drive is marked synced instead of uploaded again — including files you've since moved or renamed on your phone, and files you moved around in Drive. Optionally, it also recognizes files that reached Drive some other way

## Privacy and permissions

GoatTidi talks only to Google Drive, directly from your phone. There is no server of ours in between, and no analytics.

- **Google Drive, by default: `drive.file`.** The app can see and manage only the files it uploaded — wherever you later move them in Drive — and nothing else in your Drive.
- **Google Drive, opt-in: `drive.readonly`.** Requested only if you turn on Settings → Sync detection → *Expand sync search to other folders*, so the app can recognize files that reached Drive some other way (a computer, another sync tool) and not upload them twice. It searches the files you own across your whole Drive, or just one folder if you pick one with *Choose Drive folder…*. It reads only names, sizes, and checksums — never file contents. Turning the switch off stops the app requesting this access; to revoke the grant entirely, remove GoatTidi under your Google Account's third-party connections.
- **On the phone:** read access to photos, videos, and audio, to show them and upload the ones you choose.

## Requirements

- Android 8.0 (API 26) or newer
- A Google account with Drive
- To build it yourself: JDK 17 and the Android SDK (API 35)

GoatTidi isn't in an app store: you build it from source and install it on your own phone. Drive sign-in only works for a build whose signing key is registered in your own (free) Google Cloud project, so the debug APKs that CI attaches to each run are good for trying the UI but can't connect to Drive.

## Building

**[BUILDING.md](BUILDING.md)** walks through everything: installing Java and the Android SDK, building, the one-time Google Cloud setup, and installing on your phone.

To have an AI coding agent (Claude Code, Codex, Cursor, …) do it for you, open it in an empty folder and paste:

```text
Build and install the GoatTidi Android app on my phone from source.
Clone https://github.com/WilliamOsman/GoatTidi and follow its BUILDING.md step by step.
Ask me before installing anything on this computer. When a step needs me (Google Cloud
setup, phone settings), stop, give me exact instructions, and wait for me to confirm.
```

The agent handles the computer side and tells you exactly what to click in Google Cloud and on your phone.

For development:

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests (Robolectric + MockWebServer, no device needed)
./gradlew lintDebug
```

## Project layout

```
app/src/main/java/com/goattidi/mediasync/
  data/db/       Room database: per-file sync records (the source of truth)
  data/drive/    Drive REST client (raw OkHttp), resumable uploads, auth
  data/hash/     MD5 hashing of local files
  data/media/    MediaStore scanner
  data/repo/     Sync state repository and settings
  di/            Hilt modules
  sync/          Upload worker, Verify and Free-up-space engines, folder resolver
  ui/            Jetpack Compose screens
```

The design and its correctness rules are written up in [`docs/mobile-media-sync-developer-handoff.md`](docs/mobile-media-sync-developer-handoff.md).

## License

The code is [MIT](LICENSE)-licensed. The GoatTidi artwork — the goat icon (`GoatTidi_icon.png` and the launcher images generated from it) — is © 2026 William Osman, all rights reserved, and **not** covered by the MIT License. If you fork or redistribute the app, replace the icon with your own. See [NOTICE](NOTICE) for details.
