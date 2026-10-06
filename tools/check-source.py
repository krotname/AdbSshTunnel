"""Fast source preparation checks, not a substitute for Gradle/device tests."""
from pathlib import Path
import ast
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
files = [p for p in root.rglob('*') if p.is_file() and not any(x in p.relative_to(root).parts for x in ('.git', 'vendor', '.gradle', 'build', 'state'))]
for p in files:
    if p.suffix == '.xml': ET.parse(p)
    if p.suffix == '.py': ast.parse(p.read_text(), filename=str(p))
    if p.suffix in {'.java', '.gradle', '.sh', '.py', '.md', '.xml', '.yml'}:
        text = p.read_text()
        for pattern in (r'192\.168\.1\.', r'\bRFCY[A-Z0-9]{6,}\b', r'BEGIN (?:OPENSSH|RSA|EC) PRIVATE KEY'):
            if re.search(pattern, text): raise SystemExit(f'Private data or prohibited attribution in {p.relative_to(root)}')
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
for component in manifest.findall('application/service'):
    if component.get(ns + 'name') == 'com.hardbacknutter.sshd.SshdService':
        assert component.get(ns + 'exported') == 'false'
print(f'Source preparation checks passed: {len(files)} files; Gradle and device acceptance still required')
