# Sticky & Note shared backend

This folder is the first server-side milestone for the shared Sticky & Note service.

## Runtime

- Node.js 22
- Fastify HTTP API
- SQLite database stored in a persistent mounted directory
- Argon2 password hashing and opaque bearer sessions
- API prefix: `/api/v1`

## Initial scope

1. Health/readiness endpoint
2. Account registration and login
3. Device/session revocation
4. Private-by-account notes and categories
5. To-do lists and tasks
6. Schema migrations and automated API tests
7. Incremental sync and deletion tombstones

The first deployment must be private until TLS, authentication, database backups, and migration/restore tests are verified. Cloudflare Tunnel should route `notes.mandocloud.uk` to the local service; do not expose the database port publicly.

## Deployment principles

- Keep secrets in environment variables, never in Git.
- Mount `/data` persistently; never store the database inside the disposable container layer.
- Require HTTPS for remote access.
- Use account-scoped database queries for every user-owned resource.
- Do not enable Android sync until API tests and a real-device end-to-end test pass.
