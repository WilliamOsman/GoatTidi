# Developer Handoff: Selective Media Sync for Google Drive (Android)

## 1. Problem & Concept

Existing Android sync apps (Autosync, FolderSync) do automatic folder mirroring. There is no good app for **manually curating which media files go to Google Drive** with a clear per-file view of sync status. Users who selectively upload lose track of what's been uploaded.

**Core concept:** a gallery-style app showing all recorded media on the device, where every file has a visible, trustworthy sync status, and the user selects exactly which files to upload to Drive.

**Guiding principle:** "Synced ✓" must always mean the bytes are verified on Drive (MD5 match), never just "upload attempted."

### Correctness invariants (non-negotiable — the whole app hangs on these)

1. **SYNCED requires a proven MD5 match, not HTTP 200.** A file becomes SYNCED only when Drive returns an `md5Checksum` (from the upload response or a follow-up `files.get`) that equals the locally computed MD5. If Drive hasn't computed the checksum yet (can happen on large files right after upload), the file stays UPLOADING/unverified — never SYNCED on a 2xx alone.
2. **The MD5 must describe the bytes that were actually uploaded.** Capture size + `date_modified` when the MD5 is computed and re-check them at upload completion; if either changed, the file was edited mid-flight — discard the result and re-queue. (A "SYNCED ✓" whose MD5 doesn't match the stored bytes is the exact failure this app exists to prevent.)
3. **Never delete a local file that isn't provably still on Drive.** Reclaim-space deletes are gated on a *fresh* check that the current local MD5 still equals the verified Drive MD5 — not a stale `status == SYNCED`.
4. **All state lives in the DB; the UI only observes.** Every transition (including crash/restart recovery) is derived from persisted records, so the queue survives process death and reboot.

---

## 2. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | |
| UI | Jetpack Compose + Material 3 | Gallery grid, status badges |
| Min SDK | 26 (target latest) | |
| Media scanning | MediaStore API | Photos, videos, audio |
| Thumbnails | Coil | Handles video frames too |
| Local DB | Room (SQLite) | Sync-state source of truth |
| Background work | WorkManager + Foreground Service | Queue survives reboot; constraint-aware (Wi-Fi, battery) |
| Networking | Retrofit + OkHttp against Drive REST API v3 | Do NOT use the legacy Google API Java client — heavyweight. Use raw REST with resumable upload endpoints |
| Auth | Credential Manager + AuthorizationClient | GoogleSignIn SDK is deprecated. Request `drive.file` scope only |
| Architecture | MVVM + Repository pattern | Repos: LocalMediaRepo, DriveRepo, SyncStateRepo |
| DI | Hilt | |
| Testing | JUnit, Robolectric (MediaStore), MockWebServer (fake Drive) | |
| CI | GitHub Actions: lint, test, assemble debug APK | Distribute via sideload / CI artifacts; no Play Store needed for personal use |

---

## 3. Data Model (Room)

```kotlin
@Entity(tableName = "sync_records")
data class SyncRecord(
    @PrimaryKey val mediaStoreId: Long,
    val localUri: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateTaken: Long,
    val localMd5: String?,        // computed lazily, cached
    val driveFileId: String?,
    val driveMd5: String?,
    val uploadedAt: Long?,
    val status: SyncStatus,
    val failureReason: String?,
    val resumeSessionUri: String? // Drive resumable upload session
)

enum class SyncStatus {
    NOT_UPLOADED, QUEUED, UPLOADING, SYNCED,
    MODIFIED_SINCE_UPLOAD, FAILED, ORPHANED
}
```

Status definitions:
- **SYNCED** — Drive file exists and `driveMd5 == localMd5`
- **MODIFIED_SINCE_UPLOAD** — local file's current MD5 ≠ MD5 at upload time
- **ORPHANED** — record has a driveFileId but the file no longer exists on Drive (detected during Verify)

---

## 4. Functional Requirements

### 4.1 Media library
- Scan via MediaStore: photos, videos, audio recordings, screen recordings
- Gallery grid with thumbnails; filters by type, date, size, source folder (Camera, Screen Recorder, WhatsApp, …)
- Sort by date, size, or sync status
- Handle SD card / external volumes
- Permissions: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` (API 33+); `READ_EXTERNAL_STORAGE` fallback

### 4.2 Status badges (per file, in grid)
○ Not uploaded · ⏳ Queued · ↑ Uploading (%) · ✓ Synced · ⚠ Modified · ✗ Failed (tap = reason + retry) · 👻 Orphaned

### 4.3 Selection & upload
- Tap select, long-press + drag batch select
- Smart selects: "All unsynced", "Unsynced videos > 100 MB"
- Queue: pause / resume / cancel / reorder
- **Resumable uploads** (Drive resumable protocol) — required for multi-GB videos; persist the session URI in the DB so uploads survive process death and reboot.
  - **On resume, don't blindly re-push:** query the confirmed byte offset with a `Content-Range: bytes */TOTAL` status probe and continue from there. Resumable sessions **expire (~1 week) and can return 404/410** — treat that as "session dead," discard the URI, and restart the upload.
  - **Re-check size + `date_modified` at completion** (invariant #2) before recording the MD5; if the file moved, re-queue.
- Wi-Fi-only toggle (default ON); optional charging-only
- Foreground service with progress notification
- Retry with exponential backoff — handle **network failures** and **API rate limits** (`403 userRateLimitExceeded` / `429`) separately; back off and keep the item queued rather than failing it. Be aware of the **750 GB/day per-account upload cap**.

### 4.4 Drive organization
- User picks destination root folder (default "Phone Media"). Keep this **separate from any folder another tool manages** (e.g. a desktop ingest pipeline that treats its Drive folder as a write-only target) — this app owns its root.
- Layout option: flat / mirror local folders / by date (YYYY/MM)
- Duplicates: same name + same MD5 at destination → mark SYNCED, skip upload; same name, different content → upload with suffix
- **Content dedup (by MD5, regardless of name):** before uploading, check the MD5 against already-synced records — if identical bytes are already on Drive under any name, mark SYNCED and skip. Avoids re-uploading the same clip that lives in two folders (Camera + WhatsApp, etc.).

### 4.5 Verify & reclaim storage
- **Verify** action: batch-check Drive (`files.get` with `md5Checksum` field), update all statuses, flag orphans (record has a `driveFileId` but the file is gone from Drive)
- **Free up space** view: verified-synced files sorted by size, one-tap delete of local copies with confirmation
- **Before each delete, re-confirm** the current local MD5 still equals the verified Drive MD5 (invariant #3) — a file modified since upload must NOT be deletable as "synced"
- On **Android 11+ (API 30+)** the app cannot `File.delete()` media it didn't create — use `MediaStore.createDeleteRequest()` and handle the system consent dialog result
- Never auto-delete without explicit user action

### 4.6 Edge cases (must handle)
- Drive **storage** quota full → clear error, pause queue
- API **rate limit** (`403 userRateLimitExceeded` / `429`) → exponential backoff, keep the item queued (not failed)
- Token expiry / revoked auth → re-auth without losing queue
- **Resumable session expired** (404/410 on the session URI) → discard URI, restart that upload
- File deleted locally while queued → skip and mark
- File **modified locally** between MD5-compute and upload completion → re-queue (invariant #2)
- Same filename in different local folders
- Network loss mid-upload → resume from the confirmed offset, not restart
- App killed / phone rebooted mid-upload → resume from DB state
- **Partial media-permission grant** (Android 14 "Selected photos") → prompt for full access or handle the subset gracefully

---

## 5. Architecture Notes

```
UI (Compose) ──> ViewModels ──> Repositories ──> Room / MediaStore / Drive REST
                                     │
                              SyncEngine (WorkManager workers)
```

- **SyncEngine** is the heart: reads QUEUED records, acquires upload session, streams file with progress, verifies MD5 on completion, writes status transitions. All state transitions go through the DB — the UI just observes Flows.
- MD5 computation on large files is expensive: compute in a background worker, cache in DB, invalidate when MediaStore `date_modified` / size changes.
- Use `drive.file` OAuth scope — app only sees files it created, which simplifies the security review and user trust.

---

## 6. Build Order (recommended for agent workflow)

Each milestone's **definition of done** = its listed tests pass. Don't advance until the §1 correctness invariants are covered by tests.

1. **Sync engine first, UI last.** Correctness lives in the engine.
2. Milestone 1: Room schema + MediaStore scanner + unit tests. *Done when:* a scan populates records, and a size/`date_modified` change invalidates the cached MD5.
3. Milestone 2: Drive client (auth, resumable upload, verify) against MockWebServer. *Done when all pass:* resume-after-disconnect (offset probe), 401 refresh, rate-limit (403/429) backoff, storage-quota error, resumable-session-expired (404/410) restart, and MD5-mismatch → **not** SYNCED.
4. Milestone 3: WorkManager queue + foreground service + status transitions. *Done when:* a kill/reboot mid-upload resumes from DB state.
5. Milestone 4: Compose UI — grid, badges, selection, queue screen, settings.
6. Milestone 5: Verify + Free-up-space. *Done when:* delete is blocked on a re-verify mismatch and routes through `MediaStore.createDeleteRequest()`.
7. **One real-Drive integration pass** (a throwaway live account) before "done" — MockWebServer can't reproduce MD5-availability timing or resume-session expiry, and those are exactly the invariants that matter.
8. CI: GitHub Actions running lint + tests + debug APK artifact on every push

---

## 7. Non-Goals (v1)

- No automatic background sync of new media (manual selection is the point)
- No download / two-way sync from Drive
- No photo editing, sharing, or management features
- Single Google account only

## 8. Nice-to-Haves (v2)

- Notification: "3 new videos (450 MB) not yet backed up"
- Multiple Drive accounts
- Scheduled reminder to review unsynced media

## 9. Success Criteria

- Any media file's true sync state is visible at a glance
- **SYNCED is never set without a Drive-returned MD5 that matches the local file** (testable: a 2xx with a missing/wrong `md5Checksum` must NOT go SYNCED)
- A 2 GB video upload survives app kill, network drop, and reboot — resuming from the confirmed offset, not restarting
- Verify correctly detects Drive-side deletions (orphans) and local modifications
- **Free-up-space never deletes a local file whose current bytes aren't provably on Drive**

---

## 10. Google Cloud / OAuth setup (do this first — it blocks all uploads)

Provisioning gotchas, each of which silently breaks uploads until fixed:

1. **Enable the Drive API** on the Google Cloud project (`APIs & Services → Library → Google Drive API → Enable`). Calls return `403 SERVICE_DISABLED` until this propagates (~1–2 min).
2. **Create an *Android* OAuth client** (`Credentials → Create → OAuth client ID → Android`) keyed to the app's **package name + SHA-1 signing-cert fingerprint** — *not* a Web or Desktop client. Register both the debug and release SHA-1s.
3. **OAuth consent screen:** in **Testing** mode Google **expires the refresh token after 7 days**. Either add the account under *Test users* (fine for personal sideload) or **publish** the app — otherwise auth silently dies a week in.
4. **Scope:** request **`drive.file`** only — the app sees just the files it created. Smallest trust surface, no Google verification review required.
5. **Token storage:** keep refresh tokens in **EncryptedSharedPreferences / Android Keystore**, never plaintext.
6. **Auth stack:** Credential Manager + `AuthorizationClient` (the legacy GoogleSignIn SDK is deprecated).
