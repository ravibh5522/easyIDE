#!/bin/sh
# Data of the Containers view: {"containers":[{id,name,image,state,status}],"runningCount":N,"error":""}.
# Needs the docker CLI and a reachable daemon; anything else is reported in "error", never as a failure.
set -u
fail() { printf '{"containers":[],"runningCount":0,"error":"%s"}\n' "$1"; exit 0; }
command -v docker >/dev/null 2>&1 || fail "docker is not installed in this environment"
list=$(docker ps -a --format '{{.ID}}|{{.Names}}|{{.Image}}|{{.State}}|{{.Status}}' 2>&1) || fail "$(printf '%s' "$list" | head -n 1 | tr -d '"\\')"
printf '%s\n' "$list" | awk -F'|' '
  function q(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); gsub(/\t/, "\\t", s); gsub(/[[:cntrl:]]/, "", s); return "\"" s "\"" }
  BEGIN { printf "{\"containers\":[" }
  NF >= 5 { if (n++) printf ","; printf "{\"id\":%s,\"name\":%s,\"image\":%s,\"state\":%s,\"status\":%s}", q($1), q($2), q($3), q($4), q($5); if ($4 == "running") up++ }
  END { printf "],\"runningCount\":%d,\"error\":\"\"}\n", up + 0 }'
