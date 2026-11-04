"""Exercise actual SSH authentication/channels, ADB wire format and revocation."""
import asyncio
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import asyncssh
from adb_ssh_tunnel.core import (Gateway, Network, default_config, normalize_key,
                                 permitted, read_status, save_config, validate_config, write_json)


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class GatewayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name) / 'config'
        self.runtime = Path(self.tmp.name) / 'runtime'
        self.key = asyncssh.generate_private_key('ssh-ed25519')
        self.network = Network('wifi:Test network', '127.0.0.1', 'test0')
        self.cfg = default_config() | {'enabled': True, 'port': free_port(),
                                     'allow': [self.network.identity],
                                     'keys': [self.key.export_public_key().decode()]}
        save_config(self.cfg, self.directory)
        # ADB fixture speaks the SDK wire protocol; it is not an Android device.
        self.adb = await asyncio.start_server(self.adb_client, '127.0.0.1', 5037)
        async def close_adb():
            self.adb.close()
            await self.adb.wait_closed()
        self.addAsyncCleanup(close_adb)
        self.gateway = Gateway(self.directory, self.runtime, lambda: self.network)
        self.addAsyncCleanup(self.gateway.close)
        state = await self.gateway.tick()
        self.assertEqual(state['state'], 'listening', state)

    async def adb_client(self, reader, writer):
        try:
            count = int(await reader.readexactly(4), 16)
            request = await reader.readexactly(count)
            value = {b'host:version': b'0029', b'host:devices-l': b''}.get(request)
            if value is None:
                writer.write(b'FAIL0007invalid')
            else:
                writer.write(b'OKAY' + f'{len(value):04x}'.encode() + value)
            await writer.drain()
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()

    async def connect(self, **changes):
        options = dict(port=self.cfg['port'], username='adb', client_keys=[self.key],
                       known_hosts=([self.gateway.key().convert_to_public()], [], []),
                       agent_path=None, config=None)
        options.update(changes)
        return await asyncio.wait_for(asyncssh.connect('127.0.0.1', **options), 5)

    async def test_adb_server_exchange_and_empty_devices(self):
        async with await self.connect() as conn:
            reader, writer = await conn.open_connection('127.0.0.1', 5037)
            writer.write(b'000chost:version')
            await writer.drain()
            self.assertEqual(await reader.readexactly(12), b'OKAY00040029')
            writer.close()
            reader, writer = await conn.open_connection('127.0.0.1', 5037)
            writer.write(b'000ehost:devices-l')
            await writer.drain()
            self.assertEqual(await reader.readexactly(8), b'OKAY0000')
            writer.close()

    async def test_wrong_key_password_username_and_host_key(self):
        other = asyncssh.generate_private_key('ssh-ed25519')
        for options in ({'client_keys': [other]}, {'username': 'root'},
                        {'client_keys': [], 'password': 'rejected', 'preferred_auth': 'password'},
                        {'client_keys': [], 'password': 'rejected', 'preferred_auth': 'keyboard-interactive'}):
            with self.subTest(options=list(options)):
                with self.assertRaises(asyncssh.PermissionDenied):
                    await self.connect(**options)
        with self.assertRaises(asyncssh.HostKeyNotVerifiable):
            await self.connect(known_hosts=([other.convert_to_public()], [], []))

    async def test_forbidden_channels_and_targets(self):
        async with await self.connect() as conn:
            for host, port in [('127.0.0.1', 22), ('localhost', 5037),
                               ('127.0.0.1', 5555), ('192.0.2.1', 5037), ('::1', 5037)]:
                with self.subTest(host=host, port=port):
                    with self.assertRaises(asyncssh.ChannelOpenError):
                        await conn.open_connection(host, port)
            for options in ({}, {'command': 'id'}, {'term_type': 'xterm'},
                            {'subsystem': 'sftp'}, {'command': 'scp -t /tmp/file'},
                            {'x11_forwarding': True}):
                with self.subTest(options=options):
                    with self.assertRaises(asyncssh.ChannelOpenError):
                        await conn.create_process(**options)
            with self.assertRaises(asyncssh.ChannelOpenError):
                await conn.open_unix_connection('/tmp/forbidden')
            with self.assertRaises(asyncssh.ChannelListenError):
                await conn.forward_remote_port('127.0.0.1', 0, '127.0.0.1', 5037)
            with self.assertRaises(asyncssh.ChannelListenError):
                await conn.forward_remote_path('/tmp/forbidden-ssh', '/tmp/another')

    async def test_network_deny_closes_established_connection_and_resumes(self):
        conn = await self.connect()
        self.cfg['deny'] = [self.network.identity]
        save_config(self.cfg, self.directory)
        self.assertEqual((await self.gateway.tick())['state'], 'network_blocked')
        await asyncio.wait_for(conn.wait_closed(), 2)
        self.assertTrue(conn.is_closed())
        with self.assertRaises(OSError):
            await self.connect()
        self.cfg['deny'] = []
        save_config(self.cfg, self.directory)
        self.assertEqual((await self.gateway.tick())['state'], 'listening')
        async with await self.connect():
            pass
        self.network = Network()
        self.assertEqual((await self.gateway.tick())['state'], 'network_blocked')

    async def test_revocation_invalid_config_missing_adb_and_stop(self):
        conn = await self.connect()
        self.cfg['keys'] = []
        save_config(self.cfg, self.directory)
        self.assertEqual((await self.gateway.tick())['state'], 'no_keys')
        await asyncio.wait_for(conn.wait_closed(), 2)
        self.cfg['keys'] = [self.key.export_public_key().decode()]
        self.cfg['enabled'] = False
        save_config(self.cfg, self.directory)
        self.assertEqual((await self.gateway.tick())['state'], 'stopped')
        self.cfg['enabled'] = True
        save_config(self.cfg, self.directory)
        self.adb.close()
        await self.adb.wait_closed()
        self.assertEqual((await self.gateway.tick())['state'], 'adb_unavailable')
        (self.directory / 'config.json').write_text('{broken')
        self.assertEqual((await self.gateway.tick())['state'], 'error')
        self.assertIsNone(self.gateway.listener)

    async def test_port_conflict_reports_error_and_recovers(self):
        await self.gateway.close()
        other = await asyncio.start_server(lambda r, w: w.close(), '127.0.0.1', self.cfg['port'])
        self.assertEqual((await self.gateway.tick())['state'], 'error')
        other.close()
        await other.wait_closed()
        self.assertEqual((await self.gateway.tick())['state'], 'listening')

    async def test_host_key_and_settings_survive_restart(self):
        fingerprint = self.gateway.key().get_fingerprint()
        await self.gateway.close()
        other = Gateway(self.directory, self.runtime, lambda: self.network)
        self.assertEqual(other.key().get_fingerprint(), fingerprint)
        self.assertEqual((await other.tick())['state'], 'listening')
        await other.close()
        self.assertEqual((self.directory / 'config.json').stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.directory / 'host_ed25519').stat().st_mode & 0o777, 0o600)


class PolicyTest(unittest.TestCase):
    def test_invalid_settings_are_rejected_before_use(self):
        for value in ([], None, 'invalid', {'port': 5037}, {'port': True},
                      {'enabled': 'true'}, {'mode': 'unknown'}, {'keys': ['invalid']}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_config(value)

    def test_dead_or_stale_status_never_claims_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            value = {'state': 'listening', 'updated_at': time.time(), 'pid': os.getpid()}
            write_json(directory / 'status.json', value)
            self.assertEqual(read_status(directory)['state'], 'listening')
            write_json(directory / 'status.json', value | {'updated_at': time.time() - 30})
            self.assertEqual(read_status(directory)['state'], 'stopped')
            write_json(directory / 'status.json', value | {'pid': 2147483647})
            self.assertEqual(read_status(directory)['state'], 'stopped')
            write_json(directory / 'status.json', value | {'pid': 0})
            self.assertEqual(read_status(directory)['state'], 'error')

    def test_policy_modes_and_unknown_network(self):
        cfg = default_config()
        network = Network('wifi:Lab', '192.0.2.2', 'wlan0')
        self.assertFalse(permitted(cfg, network))
        cfg['allow'] = ['wifi:Lab']
        self.assertTrue(permitted(cfg, network))
        cfg['deny'] = ['wifi:Lab']
        self.assertFalse(permitted(cfg, network))
        cfg['mode'] = 'blocklist'
        self.assertFalse(permitted(cfg, network))
        cfg['deny'] = []
        self.assertTrue(permitted(cfg, network))
        self.assertFalse(permitted(cfg, Network()))
        cfg['mode'] = 'disabled'
        self.assertFalse(permitted(cfg, network))

    def test_import_rejects_secrets_options_multiple_and_weak_rsa(self):
        key = asyncssh.generate_private_key('ssh-ed25519')
        public = key.export_public_key().decode().strip()
        self.assertEqual(normalize_key(public + ' comment'), public)
        rsa = asyncssh.generate_private_key('ssh-rsa', key_size=3072).export_public_key().decode()
        self.assertEqual(normalize_key(rsa), rsa.strip())
        for text in (key.export_private_key().decode(), public + '\n' + public,
                     'command="id" ' + public, 'invalid',
                     asyncssh.generate_private_key('ssh-rsa', key_size=2048).export_public_key().decode()):
            with self.assertRaises(ValueError):
                normalize_key(text)


if __name__ == '__main__':
    unittest.main()
