#!/usr/bin/env python3
"""Assemble the strictly confined Snap from a git archive and pinned wheels.

Runs on the designated Linux build host (not on CI): it packs the exact
source tree plus pre-fetched Python wheels into a handcrafted snap root
(the NetMode precedent: plain snap.yaml plus mksquashfs, core24) and
records a manifest with SHA-256 hashes. Network access is only used by
the separate wheel-fetch step; packing itself is fully offline.
"""
import argparse
import hashlib
import shutil
import subprocess
import zipfile
from pathlib import Path

TRIPLETS = {'arm64': 'aarch64-linux-gnu', 'amd64': 'x86_64-linux-gnu'}

SUMMARY_LIMIT = 78


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def unpack_wheel(wheel: Path, target: Path):
    with zipfile.ZipFile(wheel) as archive:
        for member in archive.namelist():
            if member.split('/')[0].endswith('.dist-info'):
                continue
            archive.extract(member, target)


def build(architecture: str, version: str, source: Path, wheels: Path, output: Path):
    if architecture not in TRIPLETS:
        raise ValueError('Supported architectures: arm64, amd64')
    here = Path(__file__).resolve().parent
    snapdir = here / 'snap'
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    root = output / f'snap-root-{architecture}'
    if root.exists():
        shutil.rmtree(root)
    meta = root / 'meta' / 'gui'
    lib = root / 'lib' / 'adb-ssh-tunnel'
    pkg = lib / 'adb_ssh_tunnel'
    pylib = root / 'lib' / 'python'
    bin_dir = root / 'bin'
    for directory in (meta, pkg, pylib, bin_dir):
        directory.mkdir(parents=True)
    snap_yaml = (snapdir / 'snap.yaml').read_text(encoding='utf-8')
    snap_yaml = snap_yaml.replace('__VERSION__', version).replace('__ARCH__', architecture)
    summary = [line for line in snap_yaml.splitlines() if line.startswith('summary:')]
    if summary and len(summary[0]) > len('summary: ') + SUMMARY_LIMIT:
        raise ValueError('Snap summary exceeds 78 characters')
    (root / 'meta' / 'snap.yaml').write_text(snap_yaml, encoding='utf-8')
    shutil.copyfile(snapdir / 'adb-ssh-tunnel.desktop', meta / 'adb-ssh-tunnel.desktop')
    shutil.copyfile(source / 'linux' / 'packaging' / 'name.krot.adbsshtunnel.linux.svg',
                    meta / 'icon.svg')
    launcher = (snapdir / 'launcher.sh').read_text(encoding='utf-8')
    launcher = launcher.replace('__TRIPLET__', TRIPLETS[architecture])
    if '__TRIPLET__' in launcher or '__VERSION__' in snap_yaml or '__ARCH__' in snap_yaml:
        raise ValueError('Unsubstituted template placeholder')
    launcher_path = bin_dir / 'adb-ssh-tunnel'
    launcher_path.write_text(launcher, encoding='utf-8')
    shutil.copyfile(snapdir / 'snap_main.py', root / 'lib' / 'snap_main.py')
    for file in sorted((source / 'linux' / 'adb_ssh_tunnel').glob('*.py')):
        shutil.copyfile(file, pkg / file.name)
    icons = source / 'linux' / 'adb_ssh_tunnel' / 'icons'
    for file in sorted(icons.rglob('*-symbolic.svg')):
        target = pkg / 'icons' / file.relative_to(icons)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, target)
    wheel_files = sorted(wheels.glob('*.whl'))
    if not wheel_files:
        raise ValueError(f'No wheels in {wheels}')
    for wheel in wheel_files:
        unpack_wheel(wheel, pylib)
    manifest = [
        f'version: {version}',
        f'architecture: {architecture}',
        f'source: {source}',
        '',
        'wheels:',
    ]
    for wheel in wheel_files:
        manifest.append(f'  {wheel.name}  sha256:{sha256(wheel)}  bytes:{wheel.stat().st_size}')
    manifest_path = output / f'snap-{version}-{architecture}.manifest.txt'
    manifest_path.write_text('\n'.join(manifest) + '\n', encoding='utf-8')
    for path in [root, launcher_path]:
        path.chmod(0o755 if path.is_dir() or path == launcher_path else 0o644)
    for path in root.rglob('*'):
        if path.is_dir() or path == launcher_path:
            path.chmod(0o755)
        else:
            path.chmod(0o644)
    snap = output / f'adb-ssh-tunnel_{version}_{architecture}.snap'
    if snap.exists():
        snap.unlink()
    subprocess.run(['mksquashfs', str(root), str(snap), '-noappend', '-all-root',
                    '-no-xattrs', '-no-fragments', '-comp', 'xz'], check=True)
    print(snap)
    print(f'sha256: {sha256(snap)}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--arch', choices=('arm64', 'amd64'), required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--source', type=Path, required=True,
                        help='Unpacked git archive of the release source')
    parser.add_argument('--wheels', type=Path, required=True,
                        help='Directory with pinned *.whl files')
    parser.add_argument('--output', type=Path, default=Path('dist-snap'))
    options = parser.parse_args()
    build(options.arch, options.version, options.source, options.wheels, options.output)
