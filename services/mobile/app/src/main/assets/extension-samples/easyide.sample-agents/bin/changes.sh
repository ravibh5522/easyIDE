#!/bin/sh
# changes.sh: {"files":[{path,status}],"error":""} from git status: what changed in the project, agent or not.
set -u
fail() { printf '{"files":[],"error":"%s"}\n' "$1"; exit 0; }
command -v git >/dev/null 2>&1 || fail "git is not installed in this environment"
out=$(git status --porcelain 2>&1) || fail "not a git repository"
printf '%s\n' "$out" | awk '
  function q(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); gsub(/\t/, "\\t", s); gsub(/[[:cntrl:]]/, "", s); return "\"" s "\"" }
  BEGIN { printf "{\"files\":[" }
  length($0) > 3 { s = substr($0, 1, 2); gsub(/ /, "", s); if (n++) printf ","; printf "{\"path\":%s,\"status\":%s}", q(substr($0, 4)), q(s) }
  END { printf "],\"error\":\"\"}\n" }'
