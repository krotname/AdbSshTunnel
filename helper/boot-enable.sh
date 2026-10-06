#!/system/bin/sh
# service.d starts after Android boot; root ADB and SSH have separate states.
/system/bin/sh /data/adb/adb-ssh-tunnel/helper.sh enable
