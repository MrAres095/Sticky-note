#!/usr/bin/env bash
set -Eeuo pipefail

# Run as root inside a fresh Debian 12/13 LXC.
# Installs the current Sticky & Note server foundation from the feature branch.
REPO_URL="https://github.com/MrAres095/Sticky-note.git"
REPO_BRANCH="feature/server-api-foundation"
APP_DIR="/opt/sticky-note"
APP_USER="sticky-note"
DATA_DIR="/var/lib/sticky-note"
CONFIG_DIR="/etc/sticky-note"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this script as root: sudo bash install-debian.sh" >&2
  exit 1
fi
if ! grep -qi 'debian' /etc/os-release; then
  echo "This installer supports Debian 12/13 only." >&2
  exit 1
fi
if [[ -d "$APP_DIR/.git" ]]; then
  echo "$APP_DIR already contains a Git repository; refusing to overwrite it." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl git build-essential python3

# Install the Node.js 22 LTS runtime required by the backend.
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt-get install -y nodejs

node_major="$(node -p 'process.versions.node.split(".")[0]')"
if [[ "$node_major" != "22" ]]; then
  echo "Expected Node.js 22, found $(node --version)." >&2
  exit 1
fi

npm install --global pm2

if ! id "$APP_USER" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "/home/$APP_USER" --shell /usr/sbin/nologin "$APP_USER"
fi
install -d -o "$APP_USER" -g "$APP_USER" "$APP_DIR" "$DATA_DIR"
install -d -m 0750 -o root -g "$APP_USER" "$CONFIG_DIR"

git clone --depth 1 --branch "$REPO_BRANCH" "$REPO_URL" "$APP_DIR"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"
chown "$APP_USER:$APP_USER" "$DATA_DIR"

if [[ ! -f "$CONFIG_DIR/server.env" ]]; then
  pepper="$(openssl rand -hex 48)"
  cat > "$CONFIG_DIR/server.env" <<EOF
NODE_ENV=production
HOST=0.0.0.0
PORT=8080
DATA_DIR=$DATA_DIR
SESSION_PEPPER=$pepper
CORS_ORIGIN=https://notes.mandocloud.uk
EOF
  chown root:"$APP_USER" "$CONFIG_DIR/server.env"
  chmod 0640 "$CONFIG_DIR/server.env"
else
  echo "Keeping existing $CONFIG_DIR/server.env."
fi

cat > "$APP_DIR/server/ecosystem.config.cjs" <<'EOF'
module.exports = {
  apps: [{
    name: "sticky-note-server",
    script: "src/server.js",
    cwd: "/opt/sticky-note/server",
    instances: 1,
    exec_mode: "fork",
    autorestart: true,
    max_memory_restart: "300M",
    time: true,
    env: {
      NODE_ENV: "production"
    }
  }]
};
EOF
chown "$APP_USER:$APP_USER" "$APP_DIR/server/ecosystem.config.cjs"

runuser -u "$APP_USER" -- bash -lc "cd '$APP_DIR/server' && npm install --omit=dev"

cat > /etc/systemd/system/sticky-note-server.service <<EOF
[Unit]
Description=Sticky & Note API (PM2 runtime)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_DIR/server
EnvironmentFile=$CONFIG_DIR/server.env
ExecStart=/usr/bin/pm2-runtime ecosystem.config.cjs
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
ReadWritePaths=$DATA_DIR $APP_DIR/server

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now sticky-note-server.service
sleep 3

echo
echo "=== Sticky & Note server install summary ==="
node --version
npm --version
systemctl --no-pager --full status sticky-note-server.service || true
echo
echo "Local health check:"
curl --fail --silent --show-error http://127.0.0.1:8080/api/v1/health || {
  echo "Health check failed. Inspect: journalctl -u sticky-note-server -n 100 --no-pager" >&2
  exit 1
}
echo
echo "Installed. Keep the service private for now; do not configure the public tunnel until security and end-to-end tests are complete."
echo "Logs: journalctl -u sticky-note-server -f"
echo "App directory: $APP_DIR/server"
echo "Persistent SQLite data: $DATA_DIR"
