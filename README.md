# Sticky & Note

Standalone Android app combining sticky notes and Google Keep-style organization.

## Product direction

- The Android app is the first client to be polished before building the server, Chrome extension, or desktop apps.
- The note editor should become a full-screen notebook-style editor inspired by the user's WeNote reference: dark title bar, spacious lined writing area, and a bottom toolbar for note color, category, reminder, attachment, and save.
- Categories/tabs are user-defined. Do not assume special Calendar, Home, or Work tabs.
- To-do is a separate task list. Reminders belong to notes as well as any task-specific reminder behavior that is deliberately retained; the UI should not conflate the To-do list with the note-reminder feature.
- Preserve existing local notes and current Google Drive data during UI and storage changes.

## Shared server plan

The planned shared service will be hosted on the user's Proxmox LXC and exposed at `https://notes.mandocloud.uk` through the existing Cloudflare Tunnel. Keep the base URL centralized and configurable so the domain can change without rewriting the app.

Android will eventually sync with the same backend used by a Chrome extension (Windows/Linux) and native desktop apps (Windows/Linux). The server is not assumed to exist yet, so remote synchronization must not be enabled until the backend, authentication, migrations, and conflict handling have been implemented and tested.

The Android endpoint foundation is in `NotesServerConfig.kt`:
- Default base URL: `https://notes.mandocloud.uk`
- API prefix: `/api/v1`
- URL override stored in app preferences
- HTTPS required for remote endpoints; HTTP is accepted only for private LAN testing
- No credentials or secrets are hard-coded

### Planned API areas

- Authentication and device sessions
- Notes, categories, favorites, pinning, and trash/restore
- Note reminders and notification scheduling
- To-do tasks and completion state
- Attachment upload/download with stable server IDs (not device-local URI strings)
- Incremental sync, conflict resolution, and tombstones for deletions
- Backup/export and account data deletion

## Release signing / updates

Every release must use the same Android signing key. This is what allows Android to install future versions as updates instead of requiring an uninstall.

The private keystore is **not** stored in Git.

### GitHub Actions secrets

Add these repository secrets in GitHub:

- `STICKY_KEYSTORE_BASE64` — base64 contents of the release JKS
- `STICKY_KEYSTORE_PASSWORD` — keystore password
- `STICKY_KEY_ALIAS` — `sticky-notes-release`
- `STICKY_KEY_PASSWORD` — key password

The workflow at `.github/workflows/build-release.yml` automatically builds a signed APK on every push to `main`, stores it as an artifact, and publishes it as a GitHub Release.

### Android pre-server readiness

The Android CI workflow runs:

- `:app:testDebugUnitTest` — validates server URL security rules and Drive merge/deletion behavior.
- `:app:assembleDebug` — verifies that the app compiles and produces a debug APK.

The app currently includes the full-screen lined note editor, editable categories, stronger note colors, local note attachments, separate multi-line To-do entry, note/task reminders, reminder restoration after reboot, a configurable future server URL, and Google Drive note/category sync. Local notes, tasks, and categories are backed up before each write; malformed JSON is preserved for recovery rather than silently discarded.

Permanent note deletion and category deletion use sync tombstones so older Drive data cannot resurrect them. Device-local attachment URIs are deliberately not treated as cloud attachment URLs.

Before enabling the shared backend, finish the real-device acceptance check with the existing release-signed install and a real Google account:

1. Confirm existing notes and widgets survive an update.
2. Create/edit notes, attach and open a file, restart the app, and verify the attachment still opens on that device.
3. Add several To-do lines at once; check off, delete, and remove completed tasks.
4. Test allowed/denied notification permission, note/task reminder delivery, tap-through, and reboot rescheduling.
5. Sync two devices with the same Drive account; verify note/category edits, permanent deletions, and conflicts do not resurrect stale data.
6. Confirm the installed APK is signed with the same original release key before distributing an update.

The shared server URL can be changed in the app settings, but server synchronization remains disabled until the backend, authentication, migrations, conflict handling, attachment storage, and API tests are ready.

### Versioning

GitHub Actions automatically assigns an increasing `versionCode` and `1.0.x` version name for each release build.

Never commit the JKS, passwords, or `keystore.properties`.
