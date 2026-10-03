#!/usr/bin/env bash
# Smoke-tests a native build of the CLI: `cli/native-smoke.sh <binary> [expected version]`.
#
# A native image builds successfully with a resource missing, and the command that needed it then
# answers with nothing — `ankka mcp` listing no pages, the console serving 404s, `ankka init` writing
# a project with no workflows. So this runs the
# binary and asks for each thing the image has to carry, rather than trusting the build.
set -euo pipefail

bin="$1"
expected="${2:-}"
work="$(mktemp -d)"
trap 'kill "${console:-}" "${web:-}" "${listener:-}" 2>/dev/null || true; rm -rf "$work"' EXIT
export ANKKA_CONFIG="$work/config.json"   # never the developer's own ~/.ankka

fail() { echo "native smoke: $*" >&2; exit 1; }

version="$("$bin" version)"
if [ -n "$expected" ] && [ "$version" != "$expected" ]; then
  fail "version is '$version', expected '$expected'"
fi
echo "version    $version"

pages="$(printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"resources/list"}' \
  | "$bin" mcp | { grep -o '"uri":"ankka://docs/' || true; } | wc -l | tr -d ' ')"
[ "$pages" -gt 0 ] || fail "ankka mcp lists no documentation pages: the image is missing ankka/docs"
echo "mcp        $pages pages"

port=$(( 20000 + RANDOM % 20000 ))
"$bin" local console --no-open --port "$port" > "$work/console.out" 2>&1 &
console=$!
address=""
for _ in $(seq 1 50); do
  address="$(grep -o 'http://localhost:[0-9]*' "$work/console.out" || true)"
  [ -n "$address" ] && break
  sleep 0.1
done
[ -n "$address" ] || fail "the local console did not start: $(cat "$work/console.out")"
for file in / /app.js /topology.js /style.css; do
  status="$(curl -s -o /dev/null -w '%{http_code}' "$address$file" || true)"
  [ "$status" = "200" ] || fail "the console answered $status for $file: the image is missing console/"
done
# The page and the script it draws from are one release: a page with a Topology tab over a script
# from before it is a tab that does nothing, and each file would still answer 200.
curl -s "$address/" | grep -q 'id="panel-topology"' \
  || fail "the console's page has no Topology panel: the image holds a stale index.html"
curl -s "$address/app.js" | grep -q 'AnkkaTopology.view' \
  || fail "the console's script does not draw the topology: the image holds a stale app.js"
echo "console    serves its files"

for language in python typescript rust; do
  "$bin" init smoke --language "$language" --dir "$work/$language" > /dev/null \
    || fail "ankka init --language $language failed"
  for file in service.json .gitignore .mcp.json .github/workflows/deploy.yml .claude/skills/ankka/SKILL.md; do
    [ -f "$work/$language/smoke/$file" ] || fail "ankka init --language $language wrote no $file: the image is missing ankka/templates"
  done
done
echo "init       renders the python, typescript and rust templates"

mkdir -p "$work/existing"
"$bin" mcp install --scope project --dir "$work/existing" > /dev/null \
  || fail "ankka mcp install --scope project failed"
grep -q '"command": "ankka"' "$work/existing/.mcp.json" \
  || fail "ankka mcp install wrote no ankka server: $(cat "$work/existing/.mcp.json")"
echo "mcp install writes a project's .mcp.json"

# `ankka local web` runs the proxy's engine inside the image. Ports found free, never the default
# 8080, which is where a local installation's gateway listens.
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'; }
process_port="$(free_port)"
web_port="$(free_port)"
mkdir -p "$work/web"
printf '{"name":"web","service":{"image":"web:1","hosting":"web","processPort":%s}}\n' "$process_port" \
  > "$work/web/service.json"
"$bin" local web -f "$work/web/service.json" --port "$web_port" > "$work/web.out" 2>&1 &
web=$!
services=""
for _ in $(seq 1 100); do
  services="$(sed -n 's/^ANKKA_SERVICES_URL=//p' "$work/web.out")"
  [ -n "$services" ] && break
  sleep 0.1
done
[ -n "$services" ] || fail "ankka local web did not start: $(cat "$work/web.out")"
# A call to a service nobody runs is the proxy's own 503, marked as its.
answer="$(curl -s -D - -o /dev/null -w '%{http_code}' "$services/nothing/x" || true)"
case "$answer" in
  *[Xx]-[Aa]nkka-[Aa]nswered-[Bb]y:\ proxy*503) ;;
  *) fail "a call to no service was not the proxy's 503: $answer" ;;
esac
# The process is given the Host the proxy chose, not the one its client would: the restricted-header
# option reached the image.
python3 -c '
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        open(sys.argv[2], "w").write(self.headers.get("Host", ""))
        self.send_response(200); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).handle_request()
' "$process_port" "$work/host.txt" &
listener=$!
status=""
for _ in $(seq 1 50); do
  status="$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: smoke.test' "http://127.0.0.1:$web_port/" || true)"
  [ "$status" = "200" ] && break
  sleep 0.1
done
[ "$status" = "200" ] || fail "the process was not reached through ankka local web: $status"
host="$(cat "$work/host.txt" 2>/dev/null || true)"
[ "$host" = "127.0.0.1:$web_port" ] \
  || fail "the process was given Host '$host', not the proxy's: the image may lack jdk.httpclient.allowRestrictedHeaders=host"
echo "local web  answers for the proxy, and sets the process's Host"
