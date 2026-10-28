#!/usr/bin/env bash
# Builds the Linux app-image and the .deb inside Docker on Ubuntu 22.04 (ADR 0012), so they run on any Linux with glibc 2.35 or newer
# and do not depend on this machine's distribution or on dpkg-deb being installed here. The jar (the UI and every platform's database
# inside) is built on this machine; only the parts that are Linux specific, the Java runtime and the launcher, are built in the container.
#   packaging/build-linux.sh            -> dist/app/JobPilot (folder) and dist/installer/jobpilot_<version>-1_amd64.deb
# Needs: Docker, Node.js/npm and a JDK 21 for Maven on this machine.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
VERSION="${JOBPILOT_VERSION:-1.0.0}"
IMAGE="eclipse-temurin:21-jdk-jammy"
command -v docker >/dev/null || { echo "Docker is required for this build." >&2; exit 1; }
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  for j in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/default; do [ -x "$j/bin/java" ] && JAVA_HOME="$j" && break; done
fi
export JAVA_HOME

echo "==> Building the UI"
(cd frontend && npm ci --silent && npm run build --silent)
echo "==> Building the backend with the UI inside"
(cd backend && ./mvnw -q -Pbundle-ui -DskipTests clean package)
rm -rf dist && mkdir -p dist/input
cp backend/target/jobpilot-*.jar dist/input/jobpilot.jar

echo "==> Packaging in $IMAGE"
docker run --rm -v "$ROOT":/work -w /work -e VERSION="$VERSION" -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" "$IMAGE" bash -c '
  set -euo pipefail
  apt-get update -qq && apt-get install -y -qq --no-install-recommends fakeroot binutils >/dev/null
  INPUT=/work/dist/input
  . packaging/jpackage-common.sh
  ICON=(--icon packaging/icons/jobpilot.png)
  jpackage --type app-image "${JPACKAGE_COMMON[@]}" "${ICON[@]}" --dest dist/app
  jpackage --type deb "${JPACKAGE_COMMON[@]}" "${ICON[@]}" "${JPACKAGE_DEB[@]}" --resource-dir packaging/deb-resources --dest dist/installer
  chown -R "$HOST_UID:$HOST_GID" dist
'
ls -la dist/installer
echo "Done: $ROOT/dist"
