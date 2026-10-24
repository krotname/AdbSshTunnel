"""Validate the actual release manifests and native payloads for both distributions."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    config = (root / 'app/build.gradle').read_text(encoding='utf-8')
    version = re.search(r"versionName '([^']+)'", config).group(1)
    code = re.search(r'versionCode (\d+)', config).group(1)
    permission_sets = {}
    for flavor in ('full', 'rustore'):
        apks = list((root / f'app/build/outputs/apk/{flavor}/release').glob('*-unsigned.apk'))
        if len(apks) != 1:
            raise SystemExit(f'{flavor}: expected one unsigned release APK, found {len(apks)}')
        apk = apks[0]
        badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(apk)], text=True)
        package = dict(re.findall(r"(\w+)='([^']*)'", badging.splitlines()[0]))
        if (package.get('name'), package.get('versionName'), package.get('versionCode')) != (
                'name.krot.adbsshtunnel', version, code):
            raise SystemExit(f'{flavor}: unexpected package or release version: {package}')
        permissions = set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", badging))
        has_hold = 'android.permission.WRITE_SECURE_SETTINGS' in permissions
        if has_hold != (flavor == 'full'):
            raise SystemExit(f'{flavor}: incorrect WRITE_SECURE_SETTINGS declaration')
        permission_sets[flavor] = permissions
        with zipfile.ZipFile(apk) as archive:
            for abi in ('arm64-v8a', 'x86_64'):
                for name in (f'assets/{abi}/root-controller', f'lib/{abi}/libjni-dropbear.so'):
                    if archive.getinfo(name).file_size == 0:
                        raise SystemExit(f'{flavor}: empty native payload {name}')
        print(f'{flavor}: {version}, package and native payloads verified; keep-enabled={has_hold}')
    if permission_sets['full'] - {'android.permission.WRITE_SECURE_SETTINGS'} != permission_sets['rustore']:
        raise SystemExit('Unexpected permission differences between distributions')


if __name__ == '__main__':
    main()
