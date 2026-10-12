#!/system/bin/sh
# Independent AdAway recovery. No ADB, developer-settings or firewall operations.
set -u
STATE_DIR=/data/adb/adaway-watchdog
LOG=$STATE_DIR/watchdog.log
LOCK=$STATE_DIR/lock
MAX_LOG_BYTES=131072

safe_root_file() {
    [ ! -L "$1" ] && [ -f "$1" ] && [ "$(stat -c %u "$1")" = 0 ]
}
safe_root_file "$STATE_DIR/adaway-watchdog.sh" || exit 1
[ ! -L "$STATE_DIR" ] && [ "$(stat -c %u "$STATE_DIR")" = 0 ] || exit 1
case "${1:-}" in
    '') /system/bin/sh "$0" --watchdog </dev/null >/dev/null 2>&1 & exit 0 ;;
    --watchdog) ;;
    *) exit 2 ;;
esac
ticks() { sed 's/^.*) //' "/proc/$1/stat" 2>/dev/null | awk '{print $20}'; }
if ! mkdir "$LOCK" 2>/dev/null; then
    [ ! -L "$LOCK" ] && safe_root_file "$LOCK/owner" || exit 1
    read -r old_pid old_ticks < "$LOCK/owner" || exit 1
    case "$old_pid:$old_ticks" in *[!0-9:]*|:*) exit 1 ;; esac
    [ "$(ticks "$old_pid")" = "$old_ticks" ] && exit 0
    rm "$LOCK/owner" && rmdir "$LOCK" && mkdir "$LOCK" || exit 1
fi
chmod 700 "$LOCK" || exit 1
printf '%s %s\n' "$$" "$(ticks "$$")" > "$LOCK/owner" || exit 1
chmod 600 "$LOCK/owner" || exit 1
trap 'rm "$LOCK/owner"; rmdir "$LOCK"; exit 0' TERM INT EXIT
log_message() {
    if [ -e "$LOG" ]; then
        safe_root_file "$LOG" || return 1
        [ "$(stat -c %s "$LOG")" -lt "$MAX_LOG_BYTES" ] || : > "$LOG"
    fi
    printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" >> "$LOG"
    chmod 600 "$LOG"
}
. "$STATE_DIR/adaway-watchdog.sh" || exit 1
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 10; done
log_message "independent watchdog started pid=$$ interval=900"
while :; do
    next=$(( $(cut -d . -f 1 /proc/uptime) + 900 ))
    adaway_check
    remaining=$(( next - $(cut -d . -f 1 /proc/uptime) ))
    [ "$remaining" -gt 0 ] || remaining=1
    sleep "$remaining"
done
