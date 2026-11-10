"""Compile production pure-Java transport/format code and run loopback contract checks.

The pinned JSON-Java test dependency is cached outside the APK and hash verified.
"""
import argparse, hashlib, os, subprocess, urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--jdk', type=Path, required=True)
p.add_argument('--docx-sample', type=Path, help='Write a sample Word document to a new path for visual verification')
a = p.parse_args()
cache = root / '.cache/ai-tests'
cache.mkdir(parents=True, exist_ok=True)
jar = cache / 'json-20240303.jar'
if not jar.exists():
    jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30).read())
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed', 'JSON test dependency mismatch'
sources = root / 'android/app/src/main/java/win/youhoo/moss'
exe = '.exe' if os.name == 'nt' else ''
subprocess.run([str(a.jdk / ('bin/javac' + exe)), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(cache),
                str(sources / 'AiWire.java'), str(sources / 'MinutesFormat.java'), str(sources / 'MinutesDocx.java'),
                str(root / 'android/tests/AiWireTest.java'), str(root / 'android/tests/MinutesDocxTest.java')], check=True)
subprocess.run([str(a.jdk / ('bin/java' + exe)), '-Dfile.encoding=UTF-8', '-cp', os.pathsep.join([str(cache), str(jar)]), 'win.youhoo.moss.AiWireTest'], check=True)
subprocess.run([str(a.jdk / ('bin/java' + exe)), '-Dfile.encoding=UTF-8', '-cp', os.pathsep.join([str(cache), str(jar)]),
                'win.youhoo.moss.MinutesDocxTest', *([str(a.docx_sample)] if a.docx_sample else [])], check=True)
