#!/system/bin/sh
# Separate root-owned helper. Install with USB; never put SSH under UID 0.
set -eu
test "$(id -u)" = 0 || { echo 'Root required' >&2; exit 1; }
case "${1:-}" in
  status) getprop service.adb.tcp.port; exit 0 ;;
  enable|guard|check) ;;
  *) echo 'Allowed operations: status, guard, check, enable' >&2; exit 2 ;;
esac
# Preserve an existing owner-managed guard; it is responsible for dynamic TLS,
# IPv6 and trusted LAN access. Do not stack a blanket drop over its allowances.
if test -f /data/adb/phone-debug/phone-debug-firewall.sh; then
  # The existing file defines functions for its watchdog; executing it alone
  # does not apply rules. Verify the live owner-managed guard instead.
  for tool in iptables ip6tables; do
    "$tool" -w 5 -C INPUT -j PHONE_DEBUG
    "$tool" -w 5 -S PHONE_DEBUG | grep -q -- '--dport 5555 -j DROP'
    "$tool" -w 5 -C OUTPUT -p tcp -m owner --uid-owner 2000 --tcp-flags SYN,ACK SYN,ACK -j PHONE_DEBUG_OUT
    "$tool" -w 5 -S PHONE_DEBUG_OUT | grep -q -- '-j DROP'
  done
else
  if test "$1" = check; then
    for tool in iptables ip6tables; do
      "$tool" -w 5 -C INPUT -j ADB_SSH_TUNNEL
      "$tool" -w 5 -C ADB_SSH_TUNNEL -p tcp --dport 5555 -j DROP
      "$tool" -w 5 -C OUTPUT -j ADB_SSH_TUNNEL_OUT
      "$tool" -w 5 -C ADB_SSH_TUNNEL_OUT -p tcp -m owner --uid-owner 2000 --tcp-flags SYN,ACK SYN,ACK -j DROP
    done
    exit 0
  fi
  for tool in iptables ip6tables; do
    "$tool" -w 5 -N ADB_SSH_TUNNEL 2>/dev/null || true
    "$tool" -w 5 -C ADB_SSH_TUNNEL -i lo -j RETURN 2>/dev/null || "$tool" -w 5 -A ADB_SSH_TUNNEL -i lo -j RETURN
    "$tool" -w 5 -C ADB_SSH_TUNNEL -p tcp --dport 5555 -j DROP 2>/dev/null || "$tool" -w 5 -A ADB_SSH_TUNNEL -p tcp --dport 5555 -j DROP
    "$tool" -w 5 -C INPUT -j ADB_SSH_TUNNEL 2>/dev/null || "$tool" -w 5 -I INPUT 1 -j ADB_SSH_TUNNEL
    "$tool" -w 5 -N ADB_SSH_TUNNEL_OUT 2>/dev/null || true
    "$tool" -w 5 -C ADB_SSH_TUNNEL_OUT -o lo -j RETURN 2>/dev/null || "$tool" -w 5 -A ADB_SSH_TUNNEL_OUT -o lo -j RETURN
    "$tool" -w 5 -C ADB_SSH_TUNNEL_OUT -p tcp -m owner --uid-owner 2000 --tcp-flags SYN,ACK SYN,ACK -j DROP 2>/dev/null || "$tool" -w 5 -A ADB_SSH_TUNNEL_OUT -p tcp -m owner --uid-owner 2000 --tcp-flags SYN,ACK SYN,ACK -j DROP
    "$tool" -w 5 -C OUTPUT -j ADB_SSH_TUNNEL_OUT 2>/dev/null || "$tool" -w 5 -I OUTPUT 1 -j ADB_SSH_TUNNEL_OUT
  done
fi
test "$1" = enable || exit 0
# Guard must be established before enabling the legacy ADB TCP listener.
if test "$(getprop service.adb.tcp.port)" != 5555; then
  setprop service.adb.tcp.port 5555
  stop adbd
  start adbd
fi
echo 'Root ADB ready on port 5555; SSH stays under its app UID'
