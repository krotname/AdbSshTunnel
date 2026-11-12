"""Require a baseline and relevant OpenSpec edits for implementation diffs."""
from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import subprocess
import sys


CAPABILITIES = (
    'android-tunnel', 'android-network-policy', 'key-security',
    'linux-tunnel', 'development-workflow',
)
ANDROID = frozenset(('android-tunnel', 'android-network-policy', 'key-security'))
LINUX = frozenset(('linux-tunnel', 'key-security'))
WORKFLOW = frozenset(('development-workflow',))
DOC_SUFFIXES = frozenset((
    '.md', '.mdx', '.rst', '.adoc', '.txt', '.pdf', '.svg', '.png', '.jpg',
    '.jpeg', '.gif', '.webp', '.ico', '.license',
))
BEHAVIORAL_TEXT = frozenset(('AGENTS.md', 'CLAUDE.md', 'SKILL.md', 'CMakeLists.txt'))


def scope(path: str) -> frozenset[str]:
    parts = PurePosixPath(path)
    if parts.name not in BEHAVIORAL_TEXT and (
        parts.suffix.lower() in DOC_SUFFIXES or parts.name in ('LICENSE', 'NOTICE')
    ):
        return frozenset()
    if path.startswith(('app/', 'adaway/')):
        return ANDROID
    if path.startswith('linux/'):
        return LINUX
    return WORKFLOW


def spec_capability(path: str) -> str | None:
    parts = PurePosixPath(path).parts
    if len(parts) == 4 and parts[:2] == ('openspec', 'specs') and parts[-1] == 'spec.md':
        return parts[2]
    if (len(parts) == 6 and parts[:2] == ('openspec', 'changes')
            and parts[2] != 'archive' and parts[3] == 'specs' and parts[-1] == 'spec.md'):
        return parts[4]
    return None


def check(root: Path, changed: list[str] | None) -> list[str]:
    errors = []
    for capability in CAPABILITIES:
        path = root / 'openspec' / 'specs' / capability / 'spec.md'
        if not path.is_file() or not path.read_text(encoding='utf-8').strip():
            errors.append(f'Missing or empty baseline: openspec/specs/{capability}/spec.md')
    if changed is None:
        return errors
    covered = set()
    for path in changed:
        capability = spec_capability(path)
        if capability is None or not (root / path).is_file():
            continue
        if not (root / path).read_text(encoding='utf-8').strip():
            continue
        if path.startswith('openspec/changes/'):
            change_dir = root.joinpath(*PurePosixPath(path).parts[:3])
            missing = [name for name in ('proposal.md', 'design.md', 'tasks.md')
                       if not (change_dir / name).is_file()
                       or not (change_dir / name).read_text(encoding='utf-8').strip()]
            if missing:
                errors.append(f'{change_dir.name}: missing change artifacts: {", ".join(missing)}')
                continue
        covered.add(capability)
    for path in changed:
        required = scope(path)
        if required and not required.intersection(covered):
            errors.append(f'{path}: update a matching spec or active delta ({", ".join(sorted(required))})')
    return errors


def changed_paths(root: Path, base: str) -> list[str]:
    if not base or set(base) == {'0'}:
        raise ValueError('A nonempty, existing base commit is required')
    # Resolve a commit before using it as a diff operand; never treat it as an option.
    resolved = subprocess.run(
        ['git', 'rev-parse', '--verify', '--end-of-options', base + '^{commit}'],
        cwd=root, check=True, capture_output=True, text=True,
    ).stdout.strip()
    result = subprocess.run(
        ['git', 'diff', '--name-only', '-z', resolved, 'HEAD', '--'],
        cwd=root, check=True, capture_output=True,
    )
    return [path for path in result.stdout.decode('utf-8').split('\0') if path]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--base', help='Exact PR base or push-before commit')
    mode.add_argument('--snapshot', action='store_true', help='Baseline only; manual runs')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        paths = None if args.snapshot else changed_paths(root, args.base)
        errors = check(root, paths)
    except (OSError, UnicodeError, ValueError, subprocess.CalledProcessError) as error:
        print(f'OpenSpec gate could not check the diff: {error}', file=sys.stderr)
        return 1
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'OpenSpec baseline and coverage passed ({len(paths) if paths is not None else "snapshot"} changed paths)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
