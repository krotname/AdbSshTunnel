#!/system/bin/sh
# post-fs-data: firewall must precede a persisted TCP ADB listener.
/system/bin/sh /data/adb/adb-ssh-tunnel/helper.sh guard || {
    setprop service.adb.tcp.port -1
    stop adbd
    exit 1
}
