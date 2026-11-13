# Design: Linux Snap child reaping

`SnapSupervisor` хранит `Popen` (`self._proc`) запущенного ребёнка.

- `is_running()`/`pid()`: сначала owned-процесс (`poll()`), затем pid-файл.
- `stop()`: owned — `terminate`, `wait(5)`, при нужде `kill` + `wait`;
  чужой — SIGTERM, ожидание конца, SIGKILL, `waitpid(WNOHANG)` best-effort.
- `restart()` throttling без изменений.
- Тесты `test_service.py` гоняют start/stop/restart под `ResourceWarning-as-error`
  на Linux: зомби быть не должно.
