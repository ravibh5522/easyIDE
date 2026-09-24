#!/bin/sh
# session.sh <id>: {"title":..,"agent":"claude|echo","messages":[..]} for the session document.
set -u
. "$(dirname "$0")/lib.sh"
id=${1:-}
case "$id" in ''|*[!A-Za-z0-9_-]*) printf '{"title":"","agent":"","messages":[],"error":"bad session id"}\n'; exit 0;; esac
title=$(head -n 1 "$dir/$id.title" 2>/dev/null || true)
agent=echo
command -v claude >/dev/null 2>&1 && agent=claude
printf '{"title":"%s","agent":"%s","error":"","messages":' "$(printf '%s' "${title:-$id}" | json_string)" "$agent"
awk 'BEGIN { printf "[" } NF { if (n++) printf ","; printf "%s", $0 } END { printf "]" }' "$dir/$id.jsonl" 2>/dev/null || printf '[]'
printf '}\n'
