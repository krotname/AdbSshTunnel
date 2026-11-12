# Tasks: Linux Snap service backend

- [ ] 1.1 Add `linux/adb_ssh_tunnel/service.py`: Snap detection, systemd helpers, `SnapSupervisor` (start/stop/restart/PID), autostart desktop read/write, proc-route parsing and NM identity mapping. Covered by `linux/tests/test_service.py`.
- [ ] 1.2 Extend `linux/adb_ssh_tunnel/core.py`: Snap runtime dir, `snap_network()` over NM D-Bus with ioctl fallback, pure `nm_build_network()`.
- [ ] 1.3 Extend `linux/adb_ssh_tunnel/messages.py`: `snap=` repair variants for the service and stopped problems (EN+RU), Debian texts unchanged.
- [ ] 1.4 Extend `linux/adb_ssh_tunnel/ui.py`: backend dispatch, resident hide-on-close, `--hidden` start, initial/refresh reconcile, Snap diagnostics text, Snap-only Quit action.
- [ ] 1.5 Extend `linux/adb_ssh_tunnel/__main__.py`: `--hidden` flag; bump `__init__.py` to 2.6.0.
- [ ] 1.6 Add `linux/snap/`: `snap.yaml`, desktop entry, launcher, snap entry point, offline `build-snap.py` with hashed-wheel manifest.
- [ ] 1.7 Update `linux/README.md` (Snap behavior section) and `docs/RELEASE_PROFILE.md` (Snap channel).
- [ ] 1.8 Validate: `test_service.py` + `test_messages.py` locally; `python -m unittest discover -s linux/tests` on Linux CI; `check_ui.py` under Xvfb on the Linux build host; `check-openspec.py` coverage gate.
- [ ] 1.9 Hardware acceptance on Kali: Snap GUI from the exact release source, enable/tunnel/port checks, window-close survival, login-start via autostart (userd simulation plus store-revision login check).
