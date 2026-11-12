"""Validate the actual release manifests and native payloads for both distributions."""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import zipfile


ABIS = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86_64': (2, 62)}


def validate_executable(image, abi):
    elf_class, machine = ABIS[abi]
    header_size = 52 if elf_class == 1 else 64
    if (len(image) < header_size or image[:6] != b'\x7fELF' + bytes((elf_class, 1))
            or struct.unpack_from('<HH', image, 16) != (3, machine)):
        raise ValueError('invalid native executable')
    if elf_class == 1:
        phoff = struct.unpack_from('<I', image, 28)[0]
        phsize, phcount = struct.unpack_from('<HH', image, 42)
        minimum_phsize = 32
    else:
        phoff = struct.unpack_from('<Q', image, 32)[0]
        phsize, phcount = struct.unpack_from('<HH', image, 54)
        minimum_phsize = 56
    if phsize < minimum_phsize or phoff < header_size or phoff + phsize * phcount > len(image):
        raise ValueError('invalid ELF program headers')
    if not any(struct.unpack_from('<I', image, phoff + i * phsize)[0] == 3
               for i in range(phcount)):
        raise ValueError('native daemon has no ELF interpreter')


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
        if "sdkVersion:'29'" not in badging:
            raise SystemExit(f'{flavor}: Android 10 (API 29) must be supported')
        native_line = next((line for line in badging.splitlines() if line.startswith('native-code:')), '')
        if set(re.findall(r"'([^']+)'", native_line)) != set(ABIS):
            raise SystemExit(f'{flavor}: unexpected packaged ABIs: {native_line}')
        permissions = set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", badging))
        has_hold = 'android.permission.WRITE_SECURE_SETTINGS' in permissions
        if has_hold != (flavor == 'full'):
            raise SystemExit(f'{flavor}: incorrect WRITE_SECURE_SETTINGS declaration')
        permission_sets[flavor] = permissions
        with zipfile.ZipFile(apk) as archive:
            for abi in ABIS:
                for name in (f'assets/{abi}/root-controller', f'lib/{abi}/libjni-dropbear.so',
                             f'lib/{abi}/libtunnel-server.so'):
                    if archive.getinfo(name).file_size == 0:
                        raise SystemExit(f'{flavor}: empty native payload {name}')
                for name in (f'lib/{abi}/libtunnel-server.so', f'assets/{abi}/root-controller'):
                    try:
                        validate_executable(archive.read(name), abi)
                    except ValueError as error:
                        raise SystemExit(f'{flavor}: {error}: {name}') from error
        print(f'{flavor}: {version}, package and native payloads verified; keep-enabled={has_hold}')
    if permission_sets['full'] - {'android.permission.WRITE_SECURE_SETTINGS'} != permission_sets['rustore']:
        raise SystemExit('Unexpected permission differences between distributions')


if __name__ == '__main__':
    main()
