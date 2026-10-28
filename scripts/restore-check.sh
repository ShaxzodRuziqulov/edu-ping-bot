#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
backup=${1:?Usage: sh scripts/restore-check.sh backups/example.dump}
test -f "$backup"
checkdb="restorecheck_$(date -u +%Y%m%d%H%M%S)"
docker compose exec -T db createdb -U eduping "$checkdb"
trap 'docker compose exec -T db dropdb -U eduping "$checkdb"' EXIT HUP INT TERM
docker compose exec -T db pg_restore -U eduping --exit-on-error --no-owner -d "$checkdb" < "$backup"
docker compose exec -T db psql -U eduping -d "$checkdb" -v ON_ERROR_STOP=1 -c "SELECT (SELECT count(*) FROM ticket) AS tickets, (SELECT count(*) FROM ticket_message) AS messages, (SELECT count(*) FROM ticket_message WHERE archive_state='STORED' AND file_bytes IS NULL) AS missing_archives;"
