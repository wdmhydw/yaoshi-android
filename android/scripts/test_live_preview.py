"""Exercise partial sentence parsing without Android or a microphone."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--jdk', type=Path, required=True)
a = p.parse_args()
cache = root / '.cache/live-preview-tests'
cache.mkdir(parents=True, exist_ok=True)
jar = root / '.cache/ai-tests/json-20240303.jar'
if not jar.exists():
    raise SystemExit('Run android/scripts/test_ai.py once to prepare the pinned JSON test dependency.')
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
exe = '.exe' if os.name == 'nt' else ''
subprocess.run([str(a.jdk / ('bin/javac' + exe)), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(cache),
    str(root / 'android/app/src/main/java/win/youhoo/moss/LivePreview.java'),
    str(root / 'android/tests/LivePreviewTest.java')], check=True)
subprocess.run([str(a.jdk / ('bin/java' + exe)), '-Dfile.encoding=UTF-8', '-cp', os.pathsep.join([str(cache), str(jar)]),
    'win.youhoo.moss.LivePreviewTest'], check=True)
