"""Regression coverage for the 32-bit and 64-bit release payload checks."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location('release_apks', Path(__file__).with_name('check-release-apks.py'))
release_apks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_apks)


def executable(abi, interpreter=True):
    elf_class, machine = release_apks.ABIS[abi]
    header_size, phsize = (52, 32) if elf_class == 1 else (64, 56)
    image = bytearray(header_size + phsize)
    image[:6] = b'\x7fELF' + bytes((elf_class, 1))
    struct.pack_into('<HH', image, 16, 3, machine)
    struct.pack_into('<I' if elf_class == 1 else '<Q', image, 28 if elf_class == 1 else 32, header_size)
    struct.pack_into('<HH', image, 42 if elf_class == 1 else 54, phsize, 1)
    struct.pack_into('<I', image, header_size, 3 if interpreter else 1)
    return image


class NativePayloadTests(unittest.TestCase):
    def test_supported_architectures(self):
        for abi in release_apks.ABIS:
            with self.subTest(abi=abi):
                release_apks.validate_executable(executable(abi), abi)

    def test_wrong_architecture(self):
        with self.assertRaisesRegex(ValueError, 'invalid native executable'):
            release_apks.validate_executable(executable('arm64-v8a'), 'armeabi-v7a')

    def test_missing_interpreter(self):
        for abi in release_apks.ABIS:
            with self.subTest(abi=abi), self.assertRaisesRegex(ValueError, 'no ELF interpreter'):
                release_apks.validate_executable(executable(abi, False), abi)

    def test_truncated_headers(self):
        for abi in release_apks.ABIS:
            with self.subTest(abi=abi), self.assertRaisesRegex(ValueError, 'invalid ELF program headers'):
                release_apks.validate_executable(executable(abi)[:-1], abi)


if __name__ == '__main__':
    unittest.main()
