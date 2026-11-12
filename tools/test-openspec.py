"""Regression checks for missing baselines, diff coverage and Git event bases."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

MODULE = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('check-openspec.py'))
gate = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(gate)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in gate.CAPABILITIES:
            self.write(f'openspec/specs/{name}/spec.md', 'baseline')

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def test_missing_or_empty_baseline_fails_even_without_code(self):
        for content in (None, ''):
            with self.subTest(content=content):
                path = self.root / 'openspec/specs/android-tunnel/spec.md'
                if content is None:
                    path.unlink()
                else:
                    path.write_text(content)
                self.assertTrue(gate.check(self.root, None))

    def test_uncovered_code_and_instructions_fail(self):
        for path in ('app/src/main/X.java', 'linux/adb_ssh_tunnel/core.py',
                     'tools/ci-build.sh', '.github/workflows/ci.yml',
                     'package-lock.json', 'AGENTS.md', '.agents/skills/x/SKILL.md',
                     'app/src/main/cpp/CMakeLists.txt', 'openspec/config.yaml'):
            with self.subTest(path=path):
                self.assertTrue(gate.check(self.root, [path]))

    def test_docs_and_media_need_no_unrelated_spec_edit(self):
        self.assertEqual([], gate.check(self.root, ['README.md', 'docs/example.svg', 'LICENSE']))

    def test_matching_specs_cover_each_product_but_not_another_scope(self):
        spec = 'openspec/specs/android-tunnel/spec.md'
        self.assertEqual([], gate.check(self.root, ['app/src/main/X.java', spec]))
        self.assertTrue(gate.check(self.root, ['linux/adb_ssh_tunnel/core.py', spec]))
        self.assertTrue(gate.check(self.root, ['tools/ci-build.sh', spec]))

    def test_shared_key_spec_covers_both_products(self):
        self.assertEqual([], gate.check(self.root, [
            'app/src/main/KeyPolicy.java', 'linux/adb_ssh_tunnel/core.py',
            'openspec/specs/key-security/spec.md',
        ]))

    def test_deleted_archived_and_unrelated_files_cannot_cover_code(self):
        for path in ('openspec/changes/archive/old/specs/android-tunnel/spec.md',
                     'openspec/specs/android-tunnel/notes.md',
                     'openspec/changes/deleted/specs/android-tunnel/spec.md'):
            with self.subTest(path=path):
                if '/deleted/' not in path:
                    self.write(path, 'old')
                self.assertTrue(gate.check(self.root, ['app/src/main/X.java', path]))

    def test_active_delta_needs_all_change_artifacts(self):
        delta = 'openspec/changes/example/specs/linux-tunnel/spec.md'
        self.write(delta, 'delta')
        paths = ['linux/adb_ssh_tunnel/core.py', delta]
        self.assertTrue(gate.check(self.root, paths))
        for artifact in ('proposal.md', 'design.md', 'tasks.md'):
            self.write(f'openspec/changes/example/{artifact}', 'details')
        self.assertEqual([], gate.check(self.root, paths))

    def test_git_diff_includes_deleted_code_and_spaces(self):
        def git(*args):
            return subprocess.run(['git', *args], cwd=self.root, check=True,
                                  capture_output=True, text=True).stdout.strip()
        git('init', '-q')
        git('config', 'user.name', 'Test')
        git('config', 'user.email', 'test@example.invalid')
        self.write('linux/old name.py', 'old')
        git('add', '.')
        git('commit', '-qm', 'baseline')
        base = git('rev-parse', 'HEAD')
        (self.root / 'linux/old name.py').unlink()
        self.write('app/new name.java', 'new')
        git('add', '-A')
        git('commit', '-qm', 'change')
        self.assertEqual(['app/new name.java', 'linux/old name.py'], gate.changed_paths(self.root, base))
        for invalid in ('', '0' * 40, '--help', 'missing-ref'):
            with self.subTest(base=invalid):
                with self.assertRaises((ValueError, subprocess.CalledProcessError)):
                    gate.changed_paths(self.root, invalid)


if __name__ == '__main__':
    unittest.main()
