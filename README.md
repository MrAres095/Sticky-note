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

### Versioning

GitHub Actions automatically assigns an increasing `versionCode` and `1.0.x` version name for each build.

Never commit the JKS, passwords, or `keystore.properties`.
