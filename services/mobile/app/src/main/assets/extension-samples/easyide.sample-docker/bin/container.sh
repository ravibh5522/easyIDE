#!/bin/sh
# Data of a container document: {name,image,state,logs:[line],env:[{name,value}],error}. $1 is the container id.
# The logs are the last 200 lines: the view refreshes every few seconds, which is as close to `docker logs -f` as an action gets.
set -u
fail() { printf '{"name":"","image":"","state":"","logs":[],"env":[],"error":"%s"}\n' "$1"; exit 0; }
id=${1:-}
[ -n "$id" ] || fail "no container id"
command -v docker >/dev/null 2>&1 || fail "docker is not installed in this environment"
meta=$(docker inspect --format '{{.Name}}|{{.Config.Image}}|{{.State.Status}}' "$id" 2>&1) || fail "$(printf '%s' "$meta" | head -n 1 | tr -d '"\\')"
esc='function q(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); gsub(/\t/, "\\t", s); gsub(/[[:cntrl:]]/, "", s); return "\"" s "\"" }'
printf '%s\n' "$meta" | awk -F'|' "$esc"' { sub(/^\//, "", $1); printf "{\"name\":%s,\"image\":%s,\"state\":%s,\"logs\":", q($1), q($2), q($3) }'
docker logs --tail 200 "$id" 2>&1 | awk "$esc"' BEGIN { printf "[" } { if (n++) printf ","; printf "%s", q($0) } END { printf "]" }'
printf ',"env":'
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" 2>/dev/null | awk "$esc"'
  BEGIN { printf "[" }
  NF { i = index($0, "="); if (n++) printf ","; printf "{\"name\":%s,\"value\":%s}", q(substr($0, 1, i - 1)), q(substr($0, i + 1)) }
  END { printf "]" }'
printf ',"error":""}\n'
