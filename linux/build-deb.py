#!/usr/bin/env python3
"""Assemble a dependency-based .deb on a Linux build host, without compiling."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

from adb_ssh_tunnel import __version__


def build(architecture: str, output: Path):
    if architecture not in ('arm64', 'amd64'):
        raise ValueError('Supported architectures: arm64, amd64')
    source = Path(__file__).resolve().parent
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='adb-ssh-deb-') as tmp:
        root = Path(tmp) / 'root'
        lib = root / 'usr/lib/adb-ssh-tunnel/adb_ssh_tunnel'
        lib.mkdir(parents=True)
        for file in (source / 'adb_ssh_tunnel').glob('*.py'):
            shutil.copyfile(file, lib / file.name)
        mapping = {
            'adb-ssh-tunnel': 'usr/bin/adb-ssh-tunnel',
            'adb-ssh-tunnel.service': 'usr/lib/systemd/user/adb-ssh-tunnel.service',
            'name.krot.adbsshtunnel.linux.desktop': 'usr/share/applications/name.krot.adbsshtunnel.linux.desktop',
            'name.krot.adbsshtunnel.linux.svg': 'usr/share/icons/hicolor/scalable/apps/name.krot.adbsshtunnel.linux.svg',
        }
        for name, target in mapping.items():
            path = root / target
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / 'packaging' / name, path)
            path.chmod(0o755 if name == 'adb-ssh-tunnel' else 0o644)
        doc = root / 'usr/share/doc/adb-ssh-tunnel'
        doc.mkdir(parents=True)
        shutil.copyfile(source / 'README.md', doc / 'README.md')
        shutil.copyfile(source.parent / 'LICENSE', doc / 'copyright')
        control = root / 'DEBIAN'
        control.mkdir()
        (control / 'control').write_text(
            f'Package: adb-ssh-tunnel\nVersion: {__version__}\nArchitecture: {architecture}\n'
            'Maintainer: krotname <kreout@gmail.com>\nSection: devel\nPriority: optional\n'
            'Depends: python3 (>= 3.11), python3-asyncssh (>= 2.24), python3-gi, '
            'gir1.2-gtk-4.0 (>= 4.12), gir1.2-adw-1 (>= 1.4), network-manager, iproute2, adb\n'
            'Homepage: https://github.com/krotname/AdbSshTunnel\n'
            'Description: Key-only SSH gateway to the local Linux ADB server\n'
            ' Adaptive GTK interface and per-network policies. No SSH shell or file transfer.\n',
            encoding='utf-8')
        package = output / f'adb-ssh-tunnel_{__version__}_{architecture}.deb'
        subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(root), str(package)], check=True)
        print(package)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--arch', choices=('arm64', 'amd64'), required=True)
    parser.add_argument('--output', type=Path, default=Path('dist'))
    options = parser.parse_args()
    build(options.arch, options.output)
