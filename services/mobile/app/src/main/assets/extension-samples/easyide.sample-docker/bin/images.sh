#!/bin/sh
# Data of the Images view: {"images":[{name,id,size}],"error":""}.
set -u
fail() { printf '{"images":[],"error":"%s"}\n' "$1"; exit 0; }
command -v docker >/dev/null 2>&1 || fail "docker is not installed in this environment"
list=$(docker images --format '{{.Repository}}:{{.Tag}}|{{.ID}}|{{.Size}}' 2>&1) || fail "$(printf '%s' "$list" | head -n 1 | tr -d '"\\')"
printf '%s\n' "$list" | awk -F'|' '
  function q(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); gsub(/\t/, "\\t", s); gsub(/[[:cntrl:]]/, "", s); return "\"" s "\"" }
  BEGIN { printf "{\"images\":[" }
  NF >= 3 { if (n++) printf ","; printf "{\"name\":%s,\"id\":%s,\"size\":%s}", q($1), q($2), q($3) }
  END { printf "],\"error\":\"\"}\n" }'
