# Sticky & Note

Standalone Android app combining sticky notes and Google Keep-style organization.

## Release signing / updates

Every release must use the same Android signing key. This is what allows Android to install future versions as updates instead of requiring an uninstall.

The private keystore is **not** stored in Git.

### GitHub Actions secrets

Add these repository secrets in GitHub:

- `STICKY_KEYSTORE_BASE64` — base64 contents of the release JKS
- `STICKY_KEYSTORE_PASSWORD` — keystore password
- `STICKY_KEY_ALIAS` — `sticky-notes-release`
- `STICKY_KEY_PASSWORD` — key password

The workflow at `.github/workflows/build-release.yml` creates a signed release APK and, for tags such as `v1.0.1`, publishes the APK as a GitHub Release.

### Versioning

Before a new release, increase both:
- `versionCode` (must always increase)
- `versionName`

Then create a tag such as `v1.0.1`.

Never commit the JKS, passwords, or `keystore.properties`.
