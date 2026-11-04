"""Fail-closed network policy and a key-only SSH gateway to the local ADB server."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
from typing import Callable

import asyncssh
from cryptography.hazmat.primitives.serialization import load_ssh_public_key

ADB_HOST = '127.0.0.1'
ADB_PORT = 5037
USERNAME = 'adb'
APP_ID = 'name.krot.adbsshtunnel.linux'


def read_status(directory=None):
    """Never present a dead or stalled daemon's last heartbeat as a ready tunnel."""
    path = (directory or runtime_dir()) / 'status.json'
    try:
        if path.stat().st_size > 65536:
            raise ValueError('Status file is too large')
        value = json.loads(path.read_text())
        if not isinstance(value, dict):
            raise ValueError('Invalid status')
        age = time.time() - float(value.get('updated_at', 0))
        if age < 0 or age > 15:
            return {'state': 'stopped'}
        pid = int(value.get('pid', 0))
        if pid <= 0:
            raise ValueError('Invalid daemon PID')
        os.kill(pid, 0)
        return value
    except (FileNotFoundError, ProcessLookupError):
        return {'state': 'stopped'}
    except (OSError, ValueError, TypeError) as exc:
        return {'state': 'error', 'error': str(exc)[:300]}


def config_dir() -> Path:
    return Path(os.environ.get('XDG_CONFIG_HOME', Path.home() / '.config')) / 'adb-ssh-tunnel'


def runtime_dir() -> Path:
    base = os.environ.get('XDG_RUNTIME_DIR')
    return Path(base) / 'adb-ssh-tunnel' if base else config_dir() / 'runtime'


def private_dir(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink() or path.stat().st_uid != os.getuid():
        raise ValueError('Unsafe application directory')
    path.chmod(0o700)


def write_json(path: Path, data: dict) -> None:
    private_dir(path.parent)
    fd, name = tempfile.mkstemp(prefix='.write-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def default_config() -> dict:
    return {'enabled': False, 'port': 19191, 'mode': 'allowlist',
            'allow': [], 'deny': [], 'keys': [], 'language': 'system'}


def normalize_key(text: str) -> str:
    # Authorized-key options and private keys are deliberately not accepted.
    if len(text) > 16384 or '\n' in text.strip() or 'PRIVATE KEY' in text:
        raise ValueError('Import one OpenSSH public key, never a private key')
    fields = text.strip().split()
    if len(fields) < 2 or fields[0] not in ('ssh-ed25519', 'ssh-rsa',
                                         'ecdsa-sha2-nistp256', 'ecdsa-sha2-nistp384',
                                         'ecdsa-sha2-nistp521'):
        raise ValueError('Unsupported OpenSSH public key')
    key = asyncssh.import_public_key(' '.join(fields[:2]))
    if key.get_algorithm() == 'ssh-rsa':
        rsa_key = load_ssh_public_key(' '.join(fields[:2]).encode('ascii'))
        if rsa_key.key_size < 3072:
            raise ValueError('RSA public keys must have at least 3072 bits')
    return key.export_public_key('openssh').decode().strip()


def validate_config(value: dict) -> dict:
    if not isinstance(value, dict):
        raise ValueError('Settings must be a JSON object')
    result = default_config() | value
    if type(result['enabled']) is not bool or type(result['port']) is not int:
        raise ValueError('Invalid switch or port')
    if not 1024 <= result['port'] <= 65535 or result['port'] == ADB_PORT:
        raise ValueError('SSH port must be 1024–65535, excluding 5037')
    if result['mode'] not in ('allowlist', 'blocklist', 'disabled'):
        raise ValueError('Invalid network policy')
    for field in ('allow', 'deny', 'keys'):
        if not isinstance(result[field], list) or len(result[field]) > 128:
            raise ValueError('Invalid or oversized list')
        if any(not isinstance(x, str) or len(x) > 16384 for x in result[field]):
            raise ValueError('Invalid list entry')
    result['keys'] = list(dict.fromkeys(normalize_key(k) for k in result['keys']))
    if result['language'] not in ('system', 'en', 'ru'):
        raise ValueError('Invalid language')
    return result


def load_config(directory: Path | None = None) -> dict:
    path = (directory or config_dir()) / 'config.json'
    if not path.exists():
        return default_config()
    if path.stat().st_size > 262144:
        raise ValueError('Configuration is too large')
    return validate_config(json.loads(path.read_text(encoding='utf-8')))


def save_config(config: dict, directory: Path | None = None) -> None:
    write_json((directory or config_dir()) / 'config.json', validate_config(config))


@dataclass(frozen=True)
class Network:
    identity: str = ''
    address: str = ''
    interface: str = ''


def permitted(config: dict, network: Network) -> bool:
    if not network.identity or not network.address or network.identity in config['deny']:
        return False
    if config['mode'] == 'allowlist':
        return network.identity in config['allow']
    return config['mode'] == 'blocklist'


def _command(*args: str) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True,
                          timeout=3, env=dict(os.environ, LC_ALL='C')).stdout.strip()


def _fields(line: str) -> list[str]:
    return [re.sub(r'\\(.)', r'\1', field) for field in re.split(r'(?<!\\):', line)]


def current_network() -> Network:
    """Identify the IPv4 default-route interface without sending network traffic."""
    try:
        route = json.loads(_command('ip', '-j', '-4', 'route', 'get', '1.1.1.1'))[0]
        iface, address = route['dev'], route.get('prefsrc', '')
        ip = ipaddress.IPv4Address(address)
        if ip.is_loopback or ip.is_unspecified or ip.is_multicast:
            return Network()
        for line in _command('nmcli', '-t', '--escape', 'yes', '-f',
                             'UUID,TYPE,DEVICE', 'connection', 'show', '--active').splitlines():
            uuid, kind, device = _fields(line)
            if device != iface:
                continue
            if kind == '802-11-wireless':
                for wifi in _command('nmcli', '-t', '--escape', 'yes', '-f', 'ACTIVE,SSID',
                                     'device', 'wifi', 'list', 'ifname', iface,
                                     '--rescan', 'no').splitlines():
                    active, ssid = _fields(wifi)
                    if active == 'yes' and ssid:
                        return Network('wifi:' + ssid, address, iface)
            elif kind in ('802-3-ethernet', 'gsm'):
                prefix = 'ethernet:' if kind == '802-3-ethernet' else 'mobile:'
                return Network(prefix + uuid, address, iface)
        return Network()
    except (OSError, ValueError, KeyError, IndexError, subprocess.SubprocessError):
        return Network()


async def adb_request(request: str) -> str:
    reader, writer = await asyncio.wait_for(asyncio.open_connection(ADB_HOST, ADB_PORT), 2)
    try:
        payload = request.encode('ascii')
        writer.write(f'{len(payload):04x}'.encode('ascii') + payload)
        await writer.drain()
        status = await asyncio.wait_for(reader.readexactly(4), 2)
        count = int(await asyncio.wait_for(reader.readexactly(4), 2), 16)
        data = (await asyncio.wait_for(reader.readexactly(count), 2)).decode('utf-8', 'replace')
        if status != b'OKAY':
            raise ValueError('ADB refused request')
        return data
    finally:
        writer.close()
        await writer.wait_closed()


class RestrictedSSH(asyncssh.SSHServer):
    def __init__(self, gateway: 'Gateway'):
        self.gateway = gateway

    def connection_made(self, conn):
        self.connection = conn
        if len(self.gateway.connections) >= 64:
            conn.abort()
            return
        self.gateway.connections.add(conn)

    def connection_lost(self, exc):
        self.gateway.connections.discard(self.connection)

    def begin_auth(self, username):
        return True

    def public_key_auth_supported(self):
        return True

    def validate_public_key(self, username, key):
        return username == USERNAME and key in self.gateway.public_keys

    # Password, keyboard-interactive, host-based auth and every session are disabled.
    def session_requested(self):
        return False

    def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
        # Exact numeric loopback only: do not resolve arbitrary names or map Android ports.
        return dest_host == ADB_HOST and dest_port == ADB_PORT and self.gateway.active

    def server_requested(self, listen_host, listen_port):
        return False

    def unix_connection_requested(self, dest_path):
        return False

    def unix_server_requested(self, listen_path):
        return False


class Gateway:
    def __init__(self, directory: Path | None = None, runtime: Path | None = None,
                 network_provider: Callable[[], Network] = current_network):
        self.directory = directory or config_dir()
        self.runtime = runtime or runtime_dir()
        self.network_provider = network_provider
        self.listener = None
        self.connections = set()
        self.public_keys = []
        self.active = False
        self.signature = None
        self.host_key = None
        self.state = {'state': 'stopped'}

    def key(self):
        if self.host_key is None:
            private_dir(self.directory)
            path = self.directory / 'host_ed25519'
            if path.exists():
                if path.is_symlink() or path.stat().st_uid != os.getuid():
                    raise ValueError('Unsafe SSH host key')
                path.chmod(0o600)
                self.host_key = asyncssh.read_private_key(path)
            else:
                key = asyncssh.generate_private_key('ssh-ed25519')
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, 'wb') as stream:
                    stream.write(key.export_private_key('openssh'))
                self.host_key = key
        return self.host_key

    async def close(self):
        self.active = False
        listener, self.listener = self.listener, None
        if listener:
            listener.close()
        # On Python 3.12+, Server.wait_closed() also waits for accepted transports.
        # Abort those before waiting, otherwise policy changes can deadlock.
        connections = list(self.connections)
        for conn in connections:
            conn.abort()
        if connections:
            await asyncio.gather(*(c.wait_closed() for c in connections), return_exceptions=True)
        if listener:
            await listener.wait_closed()
        self.signature = None

    async def tick(self):
        try:
            cfg = load_config(self.directory)
            network = await asyncio.to_thread(self.network_provider)
            state = {'network': network.identity, 'address': network.address,
                     'interface': network.interface, 'port': cfg['port'],
                     'fingerprint': self.key().get_fingerprint(), 'connections': len(self.connections)}
            try:
                version = await adb_request('host:version')
                devices = await adb_request('host:devices-l')
                state.update(adb='ready', adb_version=version, devices=devices.strip())
            except (OSError, ValueError, asyncio.TimeoutError, asyncio.IncompleteReadError):
                state.update(adb='unavailable', devices='')
            reason = ('stopped' if not cfg['enabled'] else 'no_keys' if not cfg['keys'] else
                      'network_blocked' if not permitted(cfg, network) else
                      'adb_unavailable' if state['adb'] != 'ready' else 'listening')
            signature = (network, cfg['port'], tuple(cfg['keys'])) if reason == 'listening' else None
            if signature != self.signature or (signature and self.listener is None):
                await self.close()
                if signature:
                    self.public_keys = [asyncssh.import_public_key(k) for k in cfg['keys']]
                    self.listener = await asyncssh.create_server(
                        lambda: RestrictedSSH(self), network.address, cfg['port'],
                        family=socket.AF_INET, server_host_keys=[self.key()],
                        password_auth=False, kbdint_auth=False, host_based_auth=False,
                        public_key_auth=True, allow_pty=False, x11_forwarding=False,
                        agent_forwarding=False, allow_scp=False, login_timeout=15,
                        config=None)
                    self.signature = signature
                    self.active = True
            state['state'] = reason
            self.state = state
        except Exception as exc:
            await self.close()
            self.state = {'state': 'error', 'error': str(exc)[:300]}
        self.state.update(updated_at=time.time(), pid=os.getpid())
        write_json(self.runtime / 'status.json', self.state)
        return self.state

    async def run(self):
        private_dir(self.runtime)
        # One daemon owns the listener and state. Never compete with another instance.
        import fcntl
        with (self.runtime / 'daemon.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise SystemExit('ADB SSH Tunnel is already running')
            try:
                while True:
                    await self.tick()
                    await asyncio.sleep(1)
            finally:
                await self.close()
                write_json(self.runtime / 'status.json', {'state': 'stopped'})
