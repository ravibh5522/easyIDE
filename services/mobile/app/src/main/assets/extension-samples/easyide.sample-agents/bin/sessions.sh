#!/bin/sh
# sessions.sh: {"sessions":[{id,title,count,last}],"error":""}, newest first.
set -u
. "$(dirname "$0")/lib.sh"
printf '{"sessions":['
first=1
for f in $(ls -t "$dir"/*.jsonl 2>/dev/null); do
  id=$(basename "$f" .jsonl)
  title=$(head -n 1 "$dir/$id.title" 2>/dev/null || true)
  count=$(grep -c . "$f" 2>/dev/null || true)
  last=$(tail -n 1 "$f" | sed -n 's/.*"text":"\(.*\)"}$/\1/p')
  [ "$first" = 1 ] || printf ','
  first=0
  printf '{"id":"%s","title":"%s","count":%s,"last":"%s"}' "$id" "$(printf '%s' "${title:-$id}" | json_string)" "${count:-0}" "$last"
done
printf '],"error":""}\n'
