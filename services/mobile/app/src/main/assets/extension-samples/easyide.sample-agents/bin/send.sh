#!/bin/sh
# send.sh <id> <text>: records the message, asks the agent and prints its reply as one JSON message.
# The agent is $AGENT_COMMAND (setting easyide.sample-agents.command) with the message in $AGENT_PROMPT; when that is empty,
# `claude -p` if the claude CLI is installed, otherwise the echo agent below, which only repeats what it was sent.
set -u
. "$(dirname "$0")/lib.sh"
id=${1:-}
text=${2:-}
case "$id" in ''|*[!A-Za-z0-9_-]*) printf '{"role":"assistant","state":"error","text":"bad session id"}\n'; exit 0;; esac
mkdir -p "$dir"
printf '{"role":"user","text":"%s"}\n' "$(printf '%s' "$text" | json_string)" >> "$dir/$id.jsonl"
export AGENT_PROMPT="$text"
if [ -n "${AGENT_COMMAND:-}" ]; then
  reply=$(sh -c "$AGENT_COMMAND" 2>&1)
elif command -v claude >/dev/null 2>&1; then
  reply=$(claude -p "$text" 2>&1)
else
  reply=$(printf 'echo agent: %s\n(Install the claude CLI in this environment, or set easyide.sample-agents.command, to talk to a model.)' "$text")
fi
status=$?
state=""
[ "$status" = 0 ] || state='"state":"error",'
line=$(printf '{"role":"assistant",%s"text":"%s"}' "$state" "$(printf '%s' "$reply" | json_string)")
printf '%s\n' "$line" >> "$dir/$id.jsonl"
printf '%s\n' "$line"
