#!/usr/bin/env sh
set -eu
umask 077
cd "$(dirname "$0")/.."
mkdir -p backups
stamp=$(date -u +%Y%m%dT%H%M%SZ)
tmp="backups/eduping-$stamp.dump.partial"
trap 'rm -f "$tmp"' EXIT HUP INT TERM
docker compose exec -T db pg_dump -U eduping -d eduping -Fc > "$tmp"
test -s "$tmp"
docker compose exec -T db pg_restore --list < "$tmp" > /dev/null
mv "$tmp" "backups/eduping-$stamp.dump"
echo "Backup ready: backups/eduping-$stamp.dump"
