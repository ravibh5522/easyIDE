# Shared by the scripts of the Agents sample: where sessions live and how text becomes a JSON string.
# Sessions are plain files in the project: .easyide/agents/<id>.jsonl (one JSON message per line) and <id>.title.
dir=".easyide/agents"

# json_string: standard input as the inside of a JSON string (no surrounding quotes).
json_string() {
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/	/\\t/g' -e 's/\r//g' | tr -d '\000-\010\013\014\016-\037' | awk 'NR > 1 { printf "\\n" } { printf "%s", $0 }'
}
