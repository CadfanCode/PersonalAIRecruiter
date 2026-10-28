#!/usr/bin/env bash
# Builds the JobPilot desktop installer for the OS this runs on (ADR 0012): UI -> jar with the UI inside -> jpackage with a
# bundled Java runtime. Output in dist/. jpackage cannot cross-build: run it on each target OS (see .github/workflows/installer.yml).
#   packaging/build-installer.sh              native installer (.deb/.rpm on Linux, .dmg on macOS), plus the app-image
#   packaging/build-installer.sh app-image    only the runnable folder (no extra OS tools needed)
# Needs: JDK 21+ (with jpackage), Node.js/npm. Linux .deb needs dpkg-deb and fakeroot, .rpm needs rpmbuild.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
KIND="${1:-installer}"
VERSION="${JOBPILOT_VERSION:-1.0.0}"
OS="$(uname -s)"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jpackage" ]; then
  for j in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/default; do [ -x "$j/bin/jpackage" ] && JAVA_HOME="$j" && break; done
fi
export JAVA_HOME
JPACKAGE="$JAVA_HOME/bin/jpackage"
[ -x "$JPACKAGE" ] || { echo "jpackage not found: install a full JDK 21+." >&2; exit 1; }

echo "==> Building the UI"
(cd frontend && npm ci --silent && npm run build --silent)
echo "==> Building the backend with the UI inside"
(cd backend && ./mvnw -q -Pbundle-ui -DskipTests clean package)

INPUT="$ROOT/dist/input"
rm -rf "$ROOT/dist" && mkdir -p "$INPUT"
cp backend/target/jobpilot-*.jar "$INPUT/jobpilot.jar"

COMMON=(--name JobPilot --app-version "$VERSION" --vendor JobPilot --input "$INPUT" --main-jar jobpilot.jar
  --main-class org.springframework.boot.loader.launch.PropertiesLauncher
  --java-options "-Dloader.main=se.caibirch.jobpilot.desktop.DesktopLauncher" --java-options "-Xmx1g"
  --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.charsets,jdk.zipfs,jdk.naming.dns
  --description "Job applications, tailored and filled in for you")

ICON=()
case "$OS" in
  Linux)  [ -f packaging/icons/jobpilot.png ] && ICON=(--icon packaging/icons/jobpilot.png)
          EXTRA=(--linux-shortcut --linux-menu-group Office --linux-app-category Office); NATIVE=deb ;;
  Darwin) [ -f packaging/icons/jobpilot.icns ] && ICON=(--icon packaging/icons/jobpilot.icns)
          EXTRA=(--mac-package-name JobPilot); NATIVE=dmg ;;
  *)      [ -f packaging/icons/jobpilot.ico ] && ICON=(--icon packaging/icons/jobpilot.ico)
          EXTRA=(--win-menu --win-shortcut --win-dir-chooser --win-per-user-install); NATIVE=msi ;;
esac

echo "==> Packaging the app-image (runnable folder with its own Java)"
"$JPACKAGE" --type app-image "${COMMON[@]}" "${ICON[@]}" --dest dist/app

if [ "$KIND" = "installer" ]; then
  echo "==> Packaging the $NATIVE installer"
  "$JPACKAGE" --type "$NATIVE" "${COMMON[@]}" "${ICON[@]}" "${EXTRA[@]}" --dest dist/installer
fi
# The trimmed Java runtime must still have what the libraries load by name. jdk.security.auth is what the credential store needs to
# talk to the Linux Secret Service (dbus-java reads the user id through it); without it the store reports "none available".
if [ -f dist/app/JobPilot/lib/runtime/release ] || [ -f "dist/app/JobPilot.app/Contents/runtime/Contents/Home/release" ]; then
  REL="$(cat dist/app/JobPilot/lib/runtime/release 2>/dev/null || cat dist/app/JobPilot.app/Contents/runtime/Contents/Home/release)"
  for m in jdk.security.auth jdk.crypto.ec jdk.unsupported; do
    echo "$REL" | grep -q "$m" || { echo "The Java runtime in the app-image lacks $m" >&2; exit 1; }
  done
fi
echo "Done: $ROOT/dist"
