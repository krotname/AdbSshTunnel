"""Build with restrictive umasks and inspect the actual Debian payload modes."""
import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which('dpkg-deb'), 'Debian package tools required')
class PackageTest(unittest.TestCase):
    def test_umask_does_not_restrict_installed_files(self):
        source = Path(__file__).resolve().parents[1] / 'build-deb.py'
        spec = importlib.util.spec_from_file_location('build_deb', source)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            for mask in (0o027, 0o077):
                with self.subTest(umask=oct(mask)):
                    folder = Path(tmp) / str(mask)
                    previous = os.umask(mask)
                    try:
                        module.build('arm64', folder)
                    finally:
                        os.umask(previous)
                    package = next(folder.glob('*.deb'))
                    payload = folder / 'payload'
                    control = folder / 'control'
                    subprocess.run(['dpkg-deb', '-x', str(package), str(payload)], check=True)
                    subprocess.run(['dpkg-deb', '-e', str(package), str(control)], check=True)
                    self.assertEqual(stat.S_IMODE(control.stat().st_mode), 0o755)
                    for path in payload.rglob('*'):
                        expected = 0o755 if path.is_dir() or path == payload / 'usr/bin/adb-ssh-tunnel' else 0o644
                        self.assertEqual(stat.S_IMODE(path.stat().st_mode), expected, str(path))
