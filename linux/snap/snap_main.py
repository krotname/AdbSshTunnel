"""Snap entry point: locate the bundled app and run it."""
import sys
from pathlib import Path

LIB = Path(__file__).resolve().parent
sys.path.insert(0, str(LIB / 'adb-ssh-tunnel'))
sys.path.insert(0, str(LIB / 'python'))

from adb_ssh_tunnel.__main__ import main  # noqa: E402

raise SystemExit(main())
