"""Run record-local voice matching and manual correction contracts on the JVM."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request

root = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--jdk', type=Path, required=True)
a = p.parse_args()
cache = root / '.cache/speaker-tests'
cache.mkdir(parents=True, exist_ok=True)
jar = root / '.cache/ai-tests/json-20240303.jar'
jar.parent.mkdir(parents=True, exist_ok=True)
if not jar.exists():
    jar.write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30).read())
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
exe = '.exe' if os.name == 'nt' else ''
subprocess.run([str(a.jdk / ('bin/javac' + exe)), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(cache), str(root / 'android/app/src/main/java/win/youhoo/moss/SpeakerIdentity.java'), str(root / 'android/tests/SpeakerIdentityTest.java')], check=True)
subprocess.run([str(a.jdk / ('bin/java' + exe)), '-Dfile.encoding=UTF-8', '-cp', os.pathsep.join([str(cache), str(jar)]), 'win.youhoo.moss.SpeakerIdentityTest'], check=True)
