# Debian LXC deployment (initial test server)

This installs the current **server foundation** from `feature/server-api-foundation`. It is suitable for a private LAN test, not yet a complete replacement backend for the Android app.

## Suggested LXC

- Debian 12 or 13, amd64
- 1 vCPU, 512 MB RAM (increase if needed), 20 GB disk
- Static/reserved LAN IP
- Start with LAN-only access; do not publish the API through Cloudflare Tunnel yet

## Install

Run inside a fresh LXC as root. Review the script before executing it:

```sh
apt-get update
apt-get install -y curl
curl -fsSL https://raw.githubusercontent.com/MrAres095/Sticky-note/feature/server-api-foundation/server/deploy/install-debian.sh -o install-debian.sh
less install-debian.sh
bash install-debian.sh
```

The installer adds Node.js 22, PM2, a dedicated `sticky-note` service account, a systemd-managed PM2 runtime, a randomly generated session pepper, and persistent SQLite storage at `/var/lib/sticky-note`. Configuration is stored in `/etc/sticky-note/server.env` with restricted permissions.

## Verify

```sh
systemctl status sticky-note-server
journalctl -u sticky-note-server -n 100 --no-pager
curl http://127.0.0.1:8080/api/v1/health
```

From another LAN machine, test `http://LXC_IP:8080/api/v1/health` only if the LXC/network firewall permits it. Do not expose this HTTP endpoint to the public internet; Cloudflare Tunnel/HTTPS configuration comes later after hardening and full API tests.

## Update later

Do not run a blind `git pull` on the production data directory. Review and test code changes first, then use a controlled deployment procedure. Back up `/var/lib/sticky-note` before any upgrade.

## Current limitations

The API currently includes health, account/session basics, Keep Takeout JSON import, and an initial change-feed endpoint. Full CRUD/synchronization, attachment handling, direct Google Keep OAuth, raw Takeout ZIP upload, backup/restore automation, and Android end-to-end integration are not complete. Keep Android sync disabled until those are implemented and tested.
