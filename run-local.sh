#!/bin/sh
set -eu

cd "$(dirname "$0")"
docker compose up -d --wait postgres
export SERVER_PORT="${SERVER_PORT:-0}"
exec ./gradlew --console=plain bootRun "$@"
