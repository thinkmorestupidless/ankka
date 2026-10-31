#!/usr/bin/env bash
# Runs one service of the project on this machine, against the project's Postgres and Kafka
# (`docker compose up -d` first), in its own database and on the port its service.json names.
#
#   scripts/run.sh orders
#
# Every service run this way announces itself under its own name, so the others reach it by that
# name and `ankka local console` lists them all.
set -euo pipefail
cd "$(dirname "$0")/.."

service="${1:?usage: scripts/run.sh <service>}"
dir="services/$service"
if [ ! -f "$dir/service.json" ]; then
  echo "$service is not a service of this project: there is no $dir/service.json" >&2
  exit 1
fi

scripts/databases.sh

# A service in another language keeps a compose file of its own for its sidecar.
if [ -f "$dir/docker-compose.yml" ]; then
  docker compose -f "$dir/docker-compose.yml" up -d
fi

port=$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$dir/service.json" | head -1)
export ANKKA_DB_NAME="$service"
export ANKKA_HTTP_PORT="${port:-9000}"
export ANKKA_SERVICE_NAME="$service"
export ANKKA_KAFKA_BOOTSTRAP_SERVERS=localhost:9092

case "$(scripts/service-language.sh "$dir")" in
  scala)      exec sbt "$service/run" ;;
  python)     cd "$dir" && exec uv run python -m "$(sed -n 's/^name *= *"\(.*\)"/\1/p' pyproject.toml | head -1 | tr - _).main" ;;
  typescript) cd "$dir" && exec npm start ;;
  web)        cd "$dir" && exec ankka local web -- npm run dev ;;
  rust)       cd "$dir" && cargo module && exec docker compose up runtime ;;
  go)         cd "$dir" && exec go run . ;;
  java)       cd "$dir" && exec ./mvnw -q exec:java ;;
  kotlin)     cd "$dir" && exec ./gradlew --no-daemon -q run ;;
esac
