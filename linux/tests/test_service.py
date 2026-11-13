"""Service backend, autostart file and Snap network helpers (no GUI)."""
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from adb_ssh_tunnel import service
from adb_ssh_tunnel.service import nm_connection_identity, parse_proc_default_route
try:
    from adb_ssh_tunnel.core import nm_build_network
except ImportError:  # asyncssh is a Linux CI dependency, not needed for these helpers
    nm_build_network = None
from adb_ssh_tunnel import messages


class EnvGuard:
    def __init__(self, variables):
        self.variables = variables
        self.saved = {}

    def __enter__(self):
        for key, value in self.variables.items():
            self.saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return self

    def __exit__(self, *exc):
        for key, value in self.saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return False


class InSnapTest(unittest.TestCase):
    def test_snap_detected_by_env(self):
        with EnvGuard({'SNAP': None}):
            self.assertFalse(service.in_snap())
        with EnvGuard({'SNAP': '/snap/adb-ssh-tunnel/x1'}):
            self.assertTrue(service.in_snap())


class ProcRouteTest(unittest.TestCase):
    SAMPLE = (
        'Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n'
        'wlan0\t00000000\t0101A8C0\t0003\t0\t0\t600\t00000000\t0\t0\t0\n'
        'wlan0\t0001A8C0\t00000000\t0001\t0\t0\t600\t00FFFFFF\t0\t0\t0\n'
    )

    def test_default_interface(self):
        self.assertEqual(parse_proc_default_route(self.SAMPLE), 'wlan0')

    def test_no_default_route(self):
        text = 'Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n'
        self.assertEqual(parse_proc_default_route(text), '')

    def test_garbage_is_safe(self):
        self.assertEqual(parse_proc_default_route('not a route table\n'), '')
        self.assertEqual(parse_proc_default_route(''), '')
        self.assertEqual(parse_proc_default_route(
            'Iface\tDestination\tGateway\tFlags\nwlan0\t00000000\t00000000\tZZ\n'), '')


class NmIdentityTest(unittest.TestCase):
    def test_wifi_needs_ssid(self):
        self.assertEqual(nm_connection_identity('802-11-wireless', 'uuid-1', 'Cafe'), 'wifi:Cafe')
        self.assertEqual(nm_connection_identity('802-11-wireless', 'uuid-1', ''), '')

    def test_wired_and_mobile_use_uuid(self):
        self.assertEqual(nm_connection_identity('802-3-ethernet', 'uuid-2', ''), 'ethernet:uuid-2')
        self.assertEqual(nm_connection_identity('gsm', 'uuid-3', ''), 'mobile:uuid-3')
        self.assertEqual(nm_connection_identity('802-3-ethernet', '', ''), '')

    def test_unsupported_kinds_stay_empty(self):
        for kind in ('vpn', 'wireguard', 'bridge', '', '802-11-adhoc'):
            self.assertEqual(nm_connection_identity(kind, 'uuid-9', 'ssid'), '', kind)


class AutostartFileTest(unittest.TestCase):
    def test_write_read_remove_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            with EnvGuard({'SNAP': '/snap/x1', 'SNAP_USER_DATA': str(data)}):
                self.assertFalse(service.snap_login_start_enabled())
                self.assertEqual(service.set_snap_login_start(True), '')
                self.assertTrue(service.snap_login_start_enabled())
                path = service.snap_autostart_file()
                self.assertTrue(path.is_file())
                text = path.read_text(encoding='utf-8')
                self.assertIn('--hidden', text)
                self.assertIn('X-GNOME-Autostart-enabled=true', text)
                if os.name == 'posix':
                    self.assertEqual(stat.S_IMODE(path.stat().st_mode) & 0o777, 0o644)
                self.assertEqual(service.set_snap_login_start(False), '')
                self.assertFalse(service.snap_login_start_enabled())
                # Removing twice is not an error.
                self.assertEqual(service.set_snap_login_start(False), '')


@unittest.skipUnless(os.name == 'posix', 'POSIX process supervision')
class SnapSupervisorTest(unittest.TestCase):
    FAKE = '#!/bin/sh\n# Fake serve: stay alive until SIGTERM.\nexec sleep 300\n'

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.launcher = self.root / 'launcher'
        self.launcher.write_text(self.FAKE, encoding='utf-8')
        self.launcher.chmod(0o755)
        self.supervisor = service.SnapSupervisor(runtime_dir=self.root / 'runtime',
                                                  launcher=self.launcher,
                                                  log_path=self.root / 'serve.log')

    def tearDown(self):
        try:
            self.supervisor.stop()
        except OSError:
            pass

    def test_start_stop_roundtrip(self):
        self.assertEqual(self.supervisor.start(), '')
        self.assertTrue(self.supervisor.is_running())
        # Starting twice keeps the same child.
        first = self.supervisor.pid()
        self.assertEqual(self.supervisor.start(), '')
        self.assertEqual(self.supervisor.pid(), first)
        self.assertEqual(self.supervisor.stop(), '')
        self.assertFalse(self.supervisor.is_running())
        self.assertIsNone(self.supervisor.pid())

    def test_stop_without_child_is_clean(self):
        self.assertEqual(self.supervisor.stop(), '')

    def test_restart_replaces_child(self):
        self.assertEqual(self.supervisor.start(), '')
        first = self.supervisor.pid()
        self.assertEqual(self.supervisor.restart(), '')
        self.assertTrue(self.supervisor.is_running())
        self.assertNotEqual(self.supervisor.pid(), first)


@unittest.skipIf(nm_build_network is None, 'core requires asyncssh (Linux CI)')
class NmBuildTest(unittest.TestCase):
    def test_wifi(self):
        net = nm_build_network('802-11-wireless', 'uuid-1', 'Cafe', '192.0.2.50', 'wlan0')
        self.assertEqual((net.identity, net.address, net.interface),
                         ('wifi:Cafe', '192.0.2.50', 'wlan0'))

    def test_wired(self):
        net = nm_build_network('802-3-ethernet', 'uuid-2', '', '10.0.0.5', 'eth0')
        self.assertEqual(net.identity, 'ethernet:uuid-2')

    def test_rejects_bad_inputs(self):
        self.assertEqual(nm_build_network('vpn', 'u', '', '10.0.0.5', 'tun0').identity, '')
        self.assertEqual(nm_build_network('802-11-wireless', 'u', '', '10.0.0.5', 'wlan0').identity, '')
        self.assertEqual(nm_build_network('802-3-ethernet', 'u', '', '', 'eth0').identity, '')
        self.assertEqual(nm_build_network('802-3-ethernet', 'u', '', '127.0.0.1', 'eth0').identity, '')
        self.assertEqual(nm_build_network('802-3-ethernet', 'u', '', '0.0.0.0', 'eth0').identity, '')
        self.assertEqual(nm_build_network('802-3-ethernet', 'u', '', '224.0.0.1', 'eth0').identity, '')
        self.assertEqual(nm_build_network('802-3-ethernet', 'u', '', 'not-an-ip', 'eth0').identity, '')
        self.assertEqual(nm_build_network('802-3-ethernet', 'u', '', '10.0.0.5', '').identity, '')


class SnapMessagesTest(unittest.TestCase):
    ON = {'enabled': True, 'port': 19191}

    def test_service_error_mentions_systemd_on_deb(self):
        _, problem = messages.classify(self.ON, {'state': 'stopped'}, service_error='boom')
        self.assertIn('systemctl --user status', problem.todo[0])

    def test_service_error_snap_has_no_systemctl(self):
        _, problem = messages.classify(self.ON, {'state': 'stopped'}, service_error='boom',
                                       snap=True)
        self.assertNotIn('systemctl', problem.todo[0])
        self.assertNotIn('systemctl', problem.todo[1])
        self.assertNotIn('journalctl', problem.todo[0])

    def test_stopped_snap_has_no_journalctl(self):
        _, problem = messages.classify(self.ON, {'state': 'stopped'}, snap=True)
        self.assertNotIn('journalctl', problem.todo[0])
        self.assertNotIn('journalctl', problem.todo[1])

    def test_stopped_deb_keeps_journalctl(self):
        _, problem = messages.classify(self.ON, {'state': 'stopped'})
        self.assertIn('journalctl --user', problem.todo[0])

    def test_snap_texts_are_bilingual(self):
        problems = [
            messages.classify(self.ON, {'state': 'stopped'}, snap=True)[1],
            messages.classify(self.ON, {'state': 'stopped'}, service_error='x', snap=True)[1],
        ]
        for problem in problems:
            for pair in (problem.title, problem.what, problem.todo):
                self.assertEqual(len(pair), 2)
                self.assertTrue(all(pair), problem.key)
                self.assertNotEqual(pair[0], pair[1], problem.key)


if __name__ == '__main__':
    unittest.main()
