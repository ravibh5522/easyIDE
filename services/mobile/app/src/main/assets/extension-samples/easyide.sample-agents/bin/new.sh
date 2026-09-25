#!/bin/sh
# new.sh <title>: creates a session and prints its id.
set -eu
. "$(dirname "$0")/lib.sh"
mkdir -p "$dir"
id="s$(date +%s)$$"
printf '%s\n' "${1:-New session}" > "$dir/$id.title"
: > "$dir/$id.jsonl"
printf '%s\n' "$id"
