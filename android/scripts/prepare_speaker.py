"""Fetch the pinned offline speaker runtime/model; verify every binary before use."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, required=True)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    lock = json.loads((ROOT / 'android/dependencies.lock.json').read_text())['speaker']

    def checked(url, name, sha):
        path = args.cache / name
        def valid():
            return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == sha
        if not valid():
            temp = path.with_suffix(path.suffix + '.part')
            with urllib.request.urlopen(url, timeout=120) as response, temp.open('wb') as out:
                shutil.copyfileobj(response, out)
            if hashlib.sha256(temp.read_bytes()).hexdigest() != sha:
                raise RuntimeError('Dependency hash mismatch: ' + name)
            temp.replace(path)
        return path

    archive = checked(lock['runtime_url'], 'runtime.tar.bz2', lock['runtime_sha256'])
    model = checked(lock['model_url'], 'speaker.onnx', lock['model_sha256'])
    native = ROOT / 'android/build/speaker'
    native.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        member = next(m for m in tar.getmembers() if m.name.endswith('/arm64-v8a/libsherpa-onnx-jni.so'))
        data = tar.extractfile(member).read()
    if hashlib.sha256(data).hexdigest() != lock['native_sha256']:
        raise RuntimeError('Native library hash mismatch')
    (native / 'libsherpa-onnx-jni.so').write_bytes(data)
    assets = ROOT / 'android/app/src/main/assets/speaker'
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, assets / '3dspeaker.onnx')
    source = ROOT / 'android/app/src/main/java/com/k2fsa/sherpa/onnx'
    for name, sha in lock['bindings'].items():
        if hashlib.sha256((source / name).read_bytes()).hexdigest() != sha:
            raise RuntimeError('Upstream JNI binding mismatch: ' + name)
    print('Speaker runtime and 39.6 MB model verified; ready to build.')


if __name__ == '__main__':
    main()
