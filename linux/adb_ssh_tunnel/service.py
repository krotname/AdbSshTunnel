"""Per-user tunnel service control.

On Debian the gateway is managed through ``adb-ssh-tunnel.service`` in the
user's systemd session. Inside a strictly confined Snap, ``systemctl`` and
``systemd-run`` are unavailable, and a Snap application cannot spawn new
Snap sessions, so the graphical process supervises its own ``serve`` child
in the same Snap scope instead. The child lives as long as the resident
GUI process lives; closing the window hides it instead of quitting.

The autostart desktop entry is the application's own file inside
``$SNAP_USER_DATA/.config/autostart``: the Snap writes it when the user
enables Start at sign-in and removes it when disabled. ``snap userd``
launches it at the next login.
"""
from __future__ import annotations

import os
from pathlib import Path
import signal
import subprocess
import time

SERVICE = 'adb-ssh-tunnel.service'
AUTOSTART_DESKTOP = 'adb-ssh-tunnel.desktop'
SERVE_LOG = 'serve.log'
SERVE_PID = 'serve.pid'


def in_snap() -> bool:
    """True when running inside the strictly confined Snap."""
    return bool(os.environ.get('SNAP'))


def snap_root() -> Path | None:
    raw = os.environ.get('SNAP')
    return Path(raw) if raw else None


def snap_user_data() -> Path | None:
    raw = os.environ.get('SNAP_USER_DATA')
    return Path(raw) if raw else None


def snap_runtime_dir() -> Path:
    """Session-scoped directory for the Snap's status and PID files.

    ``XDG_RUNTIME_DIR`` is shared with the Debian package on the same
    machine, so the Snap keeps its own runtime state (including the
    daemon lock) under its private data directory instead.
    """
    data = snap_user_data()
    if data is None:  # pragma: no cover - only used inside the Snap
        raise RuntimeError('SNAP_USER_DATA is not set')
    return data / 'runtime'


def serve_log_path() -> Path | None:
    data = snap_user_data()
    return data / SERVE_LOG if data is not None else None


def snap_launcher() -> Path | None:
    root = snap_root()
    return root / 'bin' / 'adb-ssh-tunnel' if root is not None else None


def systemd_is_enabled() -> bool:
    try:
        return subprocess.run(['systemctl', '--user', 'is-enabled', SERVICE], capture_output=True,
                              text=True, timeout=2).stdout.strip() == 'enabled'
    except (OSError, subprocess.SubprocessError):
        return False


def systemd_run(action: str) -> str:
    """Run a systemctl user action; return '' on success or an error message."""
    try:
        subprocess.run(['systemctl', '--user', action, SERVICE],
                       check=True, capture_output=True, text=True, timeout=15)
        return ''
    except subprocess.CalledProcessError as exc:
        return (exc.stderr or exc.stdout or str(exc)).strip()[:300]
    except (OSError, subprocess.SubprocessError) as exc:
        return str(exc)[:300]


def snap_autostart_file() -> Path:
    data = snap_user_data()
    if data is None:  # pragma: no cover - only used inside the Snap
        raise RuntimeError('SNAP_USER_DATA is not set')
    return data / '.config' / 'autostart' / AUTOSTART_DESKTOP


_AUTOSTART_TEMPLATE = """[Desktop Entry]
Type=Application
Name=ADB SSH Tunnel
Comment=Key-only SSH tunnel to the local Linux ADB server
Exec=adb-ssh-tunnel --hidden
Terminal=false
Categories=Development;
X-GNOME-Autostart-enabled=true
"""


def snap_login_start_enabled() -> bool:
    try:
        return snap_autostart_file().is_file()
    except OSError:
        return False


def set_snap_login_start(enabled: bool) -> str:
    """Enable or disable Start at sign-in; return '' on success or an error."""
    try:
        path = snap_autostart_file()
        if enabled:
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            tmp = path.with_suffix('.desktop.tmp')
            tmp.write_text(_AUTOSTART_TEMPLATE, encoding='utf-8')
            tmp.chmod(0o644)
            os.replace(tmp, path)
        else:
            try:
                path.unlink()
            except FileNotFoundError:
                pass
        return ''
    except OSError as exc:
        return str(exc)[:300]


def parse_proc_default_route(text: str) -> str:
    """Return the default-route interface from /proc/net/route, or ''."""
    for line in text.splitlines():
        parts = line.split()
        # Iface Destination Gateway Flags ...; a default route has a zero
        # destination and the RTF_GATEWAY flag.
        try:
            gateway = len(parts) >= 4 and parts[1] == '00000000' and int(parts[3], 16) & 2
        except ValueError:
            continue
        if gateway:
            return parts[0]
    return ''


def nm_connection_identity(kind: str, uuid: str, ssid: str) -> str:
    """Map a NetworkManager connection to a policy identity, or ''."""
    if kind == '802-11-wireless' and ssid:
        return 'wifi:' + ssid
    if kind == '802-3-ethernet' and uuid:
        return 'ethernet:' + uuid
    if kind == 'gsm' and uuid:
        return 'mobile:' + uuid
    return ''


def ioctl_ipv4_address(iface: str) -> str:
    """Return the interface IPv4 address without external binaries."""
    import fcntl  # noqa: E402  (POSIX only; Snap and Linux targets)
    import socket
    import struct
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        packed = fcntl.ioctl(sock.fileno(), 0x8915, struct.pack('256s', iface.encode()[:15]))
        return socket.inet_ntoa(packed[20:24])
    finally:
        sock.close()


def _live_pid(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


class SnapSupervisor:
    """Supervise the Snap's own ``serve`` child in this process's scope."""

    def __init__(self, runtime_dir: Path | None = None, launcher: Path | None = None,
                 log_path: Path | None = None):
        data = snap_user_data()
        self.runtime_dir = runtime_dir or (data / 'runtime' if data else Path('/tmp'))
        self.launcher = launcher
        self.log_path = log_path or (data / SERVE_LOG if data else self.runtime_dir / SERVE_LOG)
        self.pid_file = self.runtime_dir / SERVE_PID
        self._last_start = 0.0

    def _resolve_launcher(self) -> Path | None:
        if self.launcher is not None:
            return self.launcher
        return snap_launcher()

    def pid(self) -> int | None:
        try:
            value = int(self.pid_file.read_text(encoding='utf-8').strip())
        except (OSError, ValueError):
            return None
        return value if value > 0 and _live_pid(value) else None

    def is_running(self) -> bool:
        return self.pid() is not None

    def start(self) -> str:
        """Start the serve child; return '' on success or an error message."""
        if self.is_running():
            return ''
        launcher = self._resolve_launcher()
        if launcher is None:
            return 'Snap launcher is not available'
        try:
            self.runtime_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            self.log_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            with open(self.log_path, 'ab') as log:
                proc = subprocess.Popen([str(launcher), 'serve'], stdin=subprocess.DEVNULL,
                                        stdout=log, stderr=subprocess.STDOUT,
                                        close_fds=True, cwd=str(self.runtime_dir))
            self.pid_file.write_text(str(proc.pid) + '\n', encoding='utf-8')
            self._last_start = time.monotonic()
            time.sleep(0.3)
            if not self.is_running():
                return 'The background tunnel process exited immediately'
            return ''
        except (OSError, subprocess.SubprocessError) as exc:
            return str(exc)[:300]

    def stop(self) -> str:
        """Stop the serve child; return '' on success or an error message."""
        pid = self.pid()
        if pid is None:
            try:
                self.pid_file.unlink()
            except OSError:
                pass
            return ''
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError as exc:
            error = str(exc)[:300]
            try:
                self.pid_file.unlink()
            except OSError:
                pass
            return '' if 'No such process' in error else error
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if not _live_pid(pid):
                break
            time.sleep(0.1)
        if _live_pid(pid):
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass
        try:
            self.pid_file.unlink()
        except OSError:
            pass
        return ''

    def restart(self) -> str:
        error = self.stop()
        if error:
            return error
        # Avoid a tight respawn loop when the child keeps failing.
        wait = 2.0 - (time.monotonic() - self._last_start)
        if wait > 0:
            time.sleep(wait)
        return self.start()
