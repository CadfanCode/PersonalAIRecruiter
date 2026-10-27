#!/usr/bin/env bash
# Removes the installed launcher and program files. Your data (database volume, ~/.jobpilot) is kept.
set -euo pipefail
rm -rf "${JOBPILOT_HOME:-$HOME/.local/share/jobpilot}"
rm -f "$HOME/.local/bin/jobpilot" "$HOME/.local/share/applications/jobpilot.desktop" \
  "$HOME/.local/share/icons/hicolor/scalable/apps/jobpilot.svg"
echo "Removed. The database volume 'personalairecruiter_jobpilot-pgdata' and ~/.jobpilot were kept."
