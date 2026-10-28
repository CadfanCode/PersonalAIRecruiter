# 0012 Desktop installer and embedded database

Date: 2026-10-28. Status: accepted. Supersedes the "Launcher, not a bundle" and "Docker stays" parts of ADR 0007.

JobPilot is now meant to be installed by anyone: one installer, nothing to set up by hand.

- **Embedded PostgreSQL.** The database runs inside the app (`io.zonky.test:embedded-postgres`, PostgreSQL 16, binaries for
  Linux, Windows and macOS x64/arm64 in the jar). Data lives in `<data-dir>/db` and survives restarts; the port is random
  per start and bound to loopback. Every Flyway migration and every Testcontainers integration test stays as it was, which
  is why this was chosen over SQLite or H2. `jobpilot.db.embedded=false` (or `JOBPILOT_DB_EMBEDDED=false`) uses the datasource
  URL instead (development with docker compose; the tests set it).
- **One jar, UI inside.** `mvnw -Pbundle-ui package` copies `frontend/dist` into `web/` in the jar; `WebUiConfig` serves it
  when `jobpilot.web-dir` is not set.
- **jpackage.** `packaging/build-installer.sh` produces an app-image and a native installer (.deb, .dmg, .msi) with a bundled
  Java runtime. The entry point is `PropertiesLauncher` with `loader.main=...desktop.DesktopLauncher`. jpackage cannot
  cross-build, so `.github/workflows/installer.yml` builds on each OS. Only Linux has been built and run by hand;
  macOS needs signing and notarization (not set up) and Windows needs WiX (preinstalled on GitHub's runners).
- **`DesktopLauncher`.** A single-instance lock in the data directory, the first free port from 18080, the backend, then
  the UI in a chromeless window of any installed Chromium-based browser (Edge ships with Windows) or Playwright's Chromium
  as the fallback. Closing the window stops the app and the database. A second launch opens another window.
- **Open limits.** The jar is about 420 MB because it holds Playwright's driver and four PostgreSQL builds; per-platform
  builds can trim it later. Playwright's Chromium (about 150 MB) is downloaded the first time it is needed.
