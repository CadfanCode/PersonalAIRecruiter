# Packaging

- `build-installer.sh`: builds the desktop installer for the OS it runs on (ADR 0012). `make-icons.sh` regenerates the icons.
- **Legacy, kept only for development on the author's Linux machine and no longer the way to install JobPilot:**
  `install.sh`, `jobpilot` (launcher script), `jobpilot.desktop`, `uninstall.sh` and `docker-compose.yml`. They describe the old
  Docker-based install; the installer replaces them. They are not used by the installer or the tests.
