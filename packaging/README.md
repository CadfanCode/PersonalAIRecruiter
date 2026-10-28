# Packaging

- `build-linux.sh`: builds the Linux app-image and the `.deb` inside Docker on Ubuntu 22.04, so they run on any Linux with glibc 2.35
  or newer and no `dpkg-deb` is needed here. `deb-resources/` holds install scripts that keep the menu entry from failing an install on
  a machine without a desktop. Output: `dist/app/JobPilot`, `dist/installer/jobpilot_<version>_amd64.deb`.
- `build-installer.sh`: builds the desktop installer for the OS it runs on, without Docker (ADR 0012). `make-icons.sh` regenerates the icons.
- **Legacy, kept only for development on the author's Linux machine and no longer the way to install JobPilot:**
  `install.sh`, `jobpilot` (launcher script), `jobpilot.desktop`, `uninstall.sh` and `docker-compose.yml`. They describe the old
  Docker-based install; the installer replaces them. They are not used by the installer or the tests.
