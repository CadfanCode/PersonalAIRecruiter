#!/usr/bin/env bash
# Builds JobPilot and installs it for the current user: a launcher icon you can pin, no terminal needed afterwards.
# Needs: Java 21+, Node.js/npm, Docker, and a Chromium-based browser (Brave is fine). Re-run it to update.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
APP="${JOBPILOT_HOME:-$HOME/.local/share/jobpilot}"
BIN="$HOME/.local/bin"
APPS="$HOME/.local/share/applications"
ICONS="$HOME/.local/share/icons/hicolor/scalable/apps"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for j in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/default; do [ -x "$j/bin/java" ] && JAVA_HOME="$j" && break; done
fi
export JAVA_HOME
command -v docker >/dev/null || { echo "Docker is required." >&2; exit 1; }
command -v npm >/dev/null || { echo "Node.js/npm is required to build the UI." >&2; exit 1; }
command -v claude >/dev/null || echo "Warning: the 'claude' CLI is not on PATH; JobPilot needs it for the AI steps."

echo "==> Building the UI"
(cd frontend && npm ci --silent && npm run build --silent)
echo "==> Building the backend (tests are run by './mvnw verify', not here)"
(cd backend && ./mvnw -q -DskipTests package)

mkdir -p "$APP" "$BIN" "$APPS" "$ICONS"
rm -rf "$APP/web" && cp -r frontend/dist "$APP/web"
cp backend/target/jobpilot-*.jar "$APP/jobpilot.jar"
cp packaging/docker-compose.yml "$APP/docker-compose.yml"
cp packaging/jobpilot.svg "$ICONS/jobpilot.svg"
sed "s|@APP@|$APP|" packaging/jobpilot > "$BIN/jobpilot" && chmod +x "$BIN/jobpilot"
sed "s|@BIN@|$BIN/jobpilot|; s|@ICON@|$ICONS/jobpilot.svg|" packaging/jobpilot.desktop > "$APPS/jobpilot.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" || true

if ! ls "$HOME"/.cache/ms-playwright/chromium-* >/dev/null 2>&1; then
  echo "==> Installing the Chromium that JobPilot uses for application forms (once)"
  (cd backend && ./mvnw -q exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium")
fi

echo
echo "Installed. Open 'JobPilot' from your application menu and pin it to the taskbar."
echo "Closing its window stops the backend and the database. Logs: ~/.jobpilot/logs"
echo "Optional settings (for example JOBPILOT_CLAUDE_MODEL=opus): ~/.config/jobpilot/env"
