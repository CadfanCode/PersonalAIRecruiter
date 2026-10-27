#!/usr/bin/env bash
# Dumps the JobPilot database from the docker compose container (SPEC 16).
set -euo pipefail
out_dir="${1:-$HOME/.jobpilot/backups}"
mkdir -p "$out_dir"
file="$out_dir/jobpilot-$(date +%Y%m%d-%H%M%S).sql.gz"
docker exec jobpilot-postgres pg_dump -U jobpilot jobpilot | gzip > "$file"
echo "Wrote $file"
