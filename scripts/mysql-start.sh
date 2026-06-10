#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/.runtime"
DATA_DIR="$RUNTIME_DIR/mysql-data"
SOCKET="$RUNTIME_DIR/mysql.sock"
PID_FILE="$RUNTIME_DIR/mysql.pid"
LOG_FILE="$RUNTIME_DIR/mysql-error.log"
MYSQLD="${MYSQLD:-/opt/homebrew/opt/mysql/bin/mysqld}"

if [[ ! -d "$DATA_DIR/mysql" ]]; then
  echo "MySQL data directory is not initialized: $DATA_DIR" >&2
  exit 1
fi

if lsof -nP -iTCP:3307 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "MySQL is already listening on 127.0.0.1:3307"
  exit 0
fi

mkdir -p "$RUNTIME_DIR"
"$MYSQLD" \
  --datadir="$DATA_DIR" \
  --port=3307 \
  --bind-address=127.0.0.1 \
  --mysqlx-port=33070 \
  --mysqlx-bind-address=127.0.0.1 \
  --mysqlx-socket="$RUNTIME_DIR/mysqlx.sock" \
  --socket="$SOCKET" \
  --pid-file="$PID_FILE" \
  --log-error="$LOG_FILE" \
  --daemonize

echo "MySQL started on 127.0.0.1:3307"
