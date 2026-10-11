# Sticky & Note shared backend

Node.js 22 + Fastify API, SQLite on persistent storage, Argon2id account passwords, and opaque bearer sessions.

## Current API foundation

- `GET /api/v1/health` — database readiness check
- `POST /api/v1/auth/register` — create an account (password 12–128 characters)
- `POST /api/v1/auth/login`, `GET /api/v1/auth/me`, `POST /api/v1/auth/logout`
- `POST /api/v1/import/keep/takeout` — import Keep note JSON records into the signed-in account
- `GET /api/v1/sync?since=0` — initial change cursor endpoint

## Google Keep import

Google Keep's official Takeout export is the initial supported migration path. The importer accepts JSON in the shape `{ "notes": [ ...Keep JSON records... ] }` or a JSON array of note records. For now, extract the Takeout archive and submit the Keep JSON records in that format; raw ZIP upload and direct Google account authorization are not enabled yet.

Example (after login, use the returned bearer token):

```sh
curl -X POST https://notes.mandocloud.uk/api/v1/import/keep/takeout \
  -H "Authorization: Bearer YOUR_SESSION_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @keep-notes.json
```

The importer preserves note text, titles, colors, archived/trashed/pinned state where available, labels (as categories), and checklist tasks/check states. It uses per-account import fingerprints so repeated imports skip previously imported records. It never deletes existing app data. Review the import summary and sample notes before relying on it as your only copy.

## Local run

Requires Node.js 22. Copy `.env.example` to `.env`, set a long random `SESSION_PEPPER`, then run:

```sh
npm install
npm start
```

The default data directory is `./data`; Docker mounts persistent storage at `/data`.

## Deployment safety

- Keep secrets in environment variables; never commit `.env`.
- Keep the service private until TLS, authentication, backups, and restore tests are verified.
- Cloudflare Tunnel should route `notes.mandocloud.uk` to the local service; do not expose SQLite or the database port publicly.
- Require HTTPS for remote access.
- Do not enable Android sync until API tests and a real-device end-to-end test pass.
