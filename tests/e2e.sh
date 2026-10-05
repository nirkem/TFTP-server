#!/usr/bin/env bash
# End-to-end tests: start the real server, drive the real client through stdin, check what it prints.
# Run `mvn package` first.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERVER_JAR="$ROOT/server/target/tftp-server.jar"
CLIENT_JAR="$ROOT/client/target/tftp-client.jar"
WORK=$(mktemp -d)
PORT=$((20000 + RANDOM % 20000))
SRV=""
BG=""
pass=0
fail=0

cleanup() {
    [ -n "$BG" ] && kill -9 "$BG" 2>/dev/null
    [ -n "$SRV" ] && kill -9 "$SRV" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

ok()  { pass=$((pass + 1)); echo "  ok    $1"; }
bad() { fail=$((fail + 1)); echo "  FAIL  $1"; shift; printf '        %s\n' "$@"; }

# check NAME EXPECTED ACTUAL: ACTUAL must contain EXPECTED
check() {
    if [[ "$3" == *"$2"* ]]; then ok "$1"; else bad "$1" "expected to contain: $2" "got: ${3:0:600}"; fi
}
check_not() {
    if [[ "$3" != *"$2"* ]]; then ok "$1"; else bad "$1" "expected NOT to contain: $2" "got: ${3:0:600}"; fi
}

start_server() {
    mkdir -p "$WORK/server/Files"
    (cd "$WORK/server" && exec java -jar "$SERVER_JAR" "$PORT" Files > server.log 2>&1) &
    SRV=$!
    for _ in $(seq 50); do
        grep -q "Serving" "$WORK/server/server.log" 2>/dev/null && return
        sleep 0.1
    done
    echo "server did not start"; cat "$WORK/server/server.log"; exit 1
}

# client DIR LINE...: run a client in DIR with one command per line. The 10s timeout turns a
# hang into a failure.
client() {
    local dir=$1; shift
    mkdir -p "$dir"
    (cd "$dir" && printf '%s\n' "$@" | timeout 10 java -jar "$CLIENT_JAR" localhost "$PORT" 2>&1)
    echo "exit=$?"
}

start_server
FILES="$WORK/server/Files"
printf 'hello from the server\n' > "$FILES/hello.txt"
head -c 1537 /dev/urandom > "$FILES/odd.bin"
mkdir -p "$WORK/alice"
head -c 1024 /dev/urandom > "$WORK/alice/exact1024.bin"

echo "a full session"
out=$(client "$WORK/alice" "LOGRQ alice" "DIRQ" "RRQ hello.txt" "RRQ odd.bin" \
    "WRQ exact1024.bin" "DIRQ" "DELRQ exact1024.bin" "DISC")
check "login is acknowledged" "ACK 0" "$out"
check "the listing shows the server's files" $'hello.txt\nodd.bin' "$out"
check "a download completes" "RRQ hello.txt complete" "$out"
check "an upload of exactly 1024 bytes completes" "WRQ exact1024.bin complete" "$out"
check "the uploader hears its own broadcast" "BCAST add exact1024.bin" "$out"
check "the uploaded file is listed" "exact1024.bin" "$out"
check "a delete is broadcast" "BCAST del exact1024.bin" "$out"
check "DISC exits cleanly" "exit=0" "$out"
if cmp -s "$FILES/odd.bin" "$WORK/alice/odd.bin"; then ok "the downloaded file is identical"; else bad "the downloaded file is identical"; fi
if [ ! -e "$FILES/exact1024.bin" ]; then ok "the deleted file is gone"; else bad "the deleted file is gone"; fi

echo "local checks never leave the client waiting"
out=$(client "$WORK/alice" "LOGRQ alice" "RRQ hello.txt" "WRQ missing.txt" "FOO" "DIRQ" "DISC")
check "downloading over a local file is refused" "File already exists" "$out"
check "uploading a missing local file is refused" "File does not exist" "$out"
check "an unknown command is refused" "Invalid command" "$out"
check "the next command still runs" "hello.txt" "$out"
check "and the client still exits" "exit=0" "$out"

echo "errors from the server"
out=$(client "$WORK/bob" "DIRQ" "LOGRQ bob" "RRQ nope.txt" "RRQ ../server.log" "DISC")
check "commands before login are refused" "Error 6" "$out"
check "a missing file is reported" "Error 1 File not found" "$out"
check "names outside Files are refused" "Error 2" "$out"
if [ ! -e "$WORK/bob/nope.txt" ]; then ok "a failed download leaves no empty file"; else bad "a failed download leaves no empty file"; fi

echo "broadcasts reach other clients"
mkdir -p "$WORK/watcher" "$WORK/carol"
rm -f "$WORK/in.fifo"; mkfifo "$WORK/in.fifo"
(cd "$WORK/watcher" && exec java -jar "$CLIENT_JAR" localhost "$PORT" < "$WORK/in.fifo" > watcher.log 2>&1) &
BG=$!
exec 3> "$WORK/in.fifo"
echo "LOGRQ watcher" >&3
sleep 1
printf 'carol was here\n' > "$WORK/carol/note.txt"
client "$WORK/carol" "LOGRQ carol" "WRQ note.txt" "DISC" > /dev/null
sleep 0.5
check "a logged-in client sees another client's upload" "BCAST add note.txt" "$(cat "$WORK/watcher/watcher.log")"
echo "DISC" >&3
exec 3>&-
wait "$BG" 2>/dev/null
BG=""

echo "end of input frees the user name"
client "$WORK/dave" "LOGRQ dave" > /dev/null
out=$(client "$WORK/dave" "LOGRQ dave" "DISC")
check_not "the name can be used again" "Error 7" "$out"

echo "the server going away"
mkdir -p "$WORK/erin"
rm -f "$WORK/in.fifo"; mkfifo "$WORK/in.fifo"
(cd "$WORK/erin" && exec timeout 10 java -jar "$CLIENT_JAR" localhost "$PORT" < "$WORK/in.fifo" > erin.log 2>&1; echo "exit=$?" >> erin.log) &
BG=$!
exec 3> "$WORK/in.fifo"
echo "LOGRQ erin" >&3
sleep 1
kill -9 "$SRV"; wait "$SRV" 2>/dev/null; SRV=""
wait "$BG" 2>/dev/null
BG=""
exec 3>&-
check "the client notices and exits" "Connection closed by the server" "$(cat "$WORK/erin/erin.log")"
check_not "without waiting for a timeout" "exit=124" "$(cat "$WORK/erin/erin.log")"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
