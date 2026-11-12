#!/bin/sh
# Snap launcher: platform environment, then the app. TRIPLET is filled
# in by linux/build-snap.py for the target architecture.
set -eu
TRIPLET=__TRIPLET__
export LD_LIBRARY_PATH="$SNAP/gnome-platform/usr/lib/$TRIPLET:$SNAP/gnome-platform/usr/lib:$SNAP/gpu-2404/usr/lib/$TRIPLET:$SNAP/gpu-2404/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export GI_TYPELIB_PATH="$SNAP/gnome-platform/usr/lib/$TRIPLET/girepository-1.0"
export PYTHONPATH="$SNAP/lib/python:$SNAP/gnome-platform/usr/lib/python3/dist-packages${PYTHONPATH:+:$PYTHONPATH}"
export XDG_DATA_DIRS="$SNAP/gnome-platform/usr/share:${XDG_DATA_DIRS:-/usr/share}"
if ! snapctl is-connected gpu-2404; then
  echo 'Connect adb-ssh-tunnel:gpu-2404 to mesa-2404 before starting ADB SSH Tunnel.' >&2
  exit 1
fi
exec "$SNAP/gpu-2404/bin/gpu-2404-provider-wrapper" /usr/bin/python3 "$SNAP/lib/snap_main.py" "$@"
