# Tasks: Linux Snap child reaping

- [ ] 1.1 Extend `SnapSupervisor` with owned-child `Popen` tracking and reaping `stop()`; keep pidfile adoption path.
- [ ] 1.2 Validate: `test_service.py` green locally; full `discover -s linux/tests` in the isolated Black container with `ResourceWarning` as error; `check-openspec.py` coverage gate.
