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
- Destination folder of your choice (default **Phone Media**), laid out flat, by month, or by source folder (Camera, WhatsApp, …)
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

GoatTidi isn't on the Play Store; you build and sideload it yourself. Drive sign-in only works for an APK whose signing key is registered with your own Google Cloud OAuth client (next sections). The debug APKs that CI attaches to each run are signed with a throwaway key, so they're useful for trying the UI but can't connect to Drive.

## Building

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests (Robolectric + MockWebServer, no device needed)
./gradlew lintDebug
```

## Google Cloud setup (required for Drive access)

Drive sign-in needs an OAuth client tied to *your* build's signing key. Each of these steps silently breaks uploads if missed:

1. **Create a Google Cloud project and enable the Google Drive API** (*APIs & Services → Library → Google Drive API → Enable*). Calls fail with `SERVICE_DISABLED` until it propagates, which takes a minute or two.
2. **Create an Android OAuth client** (*Credentials → Create credentials → OAuth client ID → Android*) with package name `com.goattidi.mediasync` and the SHA-1 of the key that signs your APK. For debug builds:
   ```sh
   ./gradlew signingReport
   ```
   Register both your debug and release SHA-1s if you use both. It must be an **Android** client — not Web or Desktop.
3. **Configure the OAuth consent screen.** In *Testing* mode, add your Google account under *Test users*. Google can expire authorizations for testing-mode apps after 7 days — if Drive keeps asking you to reconnect weekly, publishing the consent screen avoids it.
4. **Scopes:** `drive.file` needs no Google review. `drive.readonly` is a *restricted* scope: fine for your own test users, but publishing an app that requests it requires Google's verification.

No client ID or secret goes in the code — Android OAuth clients are matched by package name and signing key.

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

[MIT](LICENSE)
