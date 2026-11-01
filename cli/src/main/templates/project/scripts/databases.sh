#!/usr/bin/env bash
# Gives each service of the project its own database in the project's Postgres, and ankka's schema
# in it. Safe to run again: a database that exists is kept, and the schema is applied with
# CREATE ... IF NOT EXISTS throughout.
#
# The schema is target/ddl, which `sbt schema` writes from the ankka-runtime artifact; it is written
# here when it is missing. Run `sbt schema` again after upgrading ankka.
set -euo pipefail
cd "$(dirname "$0")/.."

ddl=target/ddl
if [ ! -d "$ddl" ] || [ -z "$(ls -A "$ddl" 2>/dev/null)" ]; then
  sbt -batch schema
fi

psql_in() { docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U ankka "$@"; }

for d in services/*/; do
  [ -f "$d/service.json" ] || continue
  name=$(basename "$d")
  exists=$(psql_in -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$name'")
  if [ "$exists" != "1" ]; then
    psql_in -d postgres -c "CREATE DATABASE \"$name\"" >/dev/null ||
      { echo "could not create the database of $name" >&2; exit 1; }
  fi
  for sql in "$ddl"/*.sql; do
    psql_in -q -d "$name" < "$sql" >/dev/null ||
      { echo "could not apply $(basename "$sql") to the database of $name" >&2; exit 1; }
  done
  echo "database $name: ready"
done
