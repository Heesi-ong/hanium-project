#!/usr/bin/env bash
set -euo pipefail

MYSQLADMIN="${MYSQLADMIN:-/opt/homebrew/opt/mysql/bin/mysqladmin}"

if ! lsof -nP -iTCP:3307 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "MySQL is not running on port 3307"
  exit 0
fi

MYSQL_PID="$(lsof -nP -t -iTCP:3307 -sTCP:LISTEN | head -n 1)"
"$MYSQLADMIN" -h 127.0.0.1 -P 3307 -u root shutdown

for _ in {1..30}; do
  if ! kill -0 "$MYSQL_PID" >/dev/null 2>&1; then
    echo "MySQL stopped"
    exit 0
  fi
  sleep 1
done

echo "MySQL shutdown timed out" >&2
exit 1
