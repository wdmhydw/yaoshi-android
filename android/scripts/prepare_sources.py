"""Fetch pinned upstream files, verifying each Git blob hash. No branch heads."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]

def fetch(url):
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'MOSS-Android-build'})
            with urllib.request.urlopen(req, timeout=45) as r: return r.read()
        except Exception:
            if attempt == 3: raise
            time.sleep(attempt + 1)

def blob_hash(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--verify-only', action='store_true', help='Use cached trees; no network or writes')
    a = p.parse_args()
    lock = json.loads((ROOT/'android/dependencies.lock.json').read_text(encoding='utf8'))
    cache = ROOT/'.cache/android-source-trees'
    if not a.verify_only: cache.mkdir(parents=True, exist_ok=True)
    for dep in lock['sources']:
        repo, commit = dep['repo'], dep['commit']
        if not re.fullmatch(r'[0-9a-f]{40}', commit): raise ValueError('Expected a full Git revision')
        manifest = cache/(repo.replace('/', '-')+'-'+commit+'.json')
        if manifest.exists(): tree = json.loads(manifest.read_text(encoding='utf8'))
        elif a.verify_only: raise RuntimeError('Missing cached tree: '+str(manifest))
        else:
            tree = json.loads(fetch(f'https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1'))
            if tree.get('truncated'): raise RuntimeError('Incomplete upstream tree')
            manifest.write_text(json.dumps(tree), encoding='utf8')
        entries = [e for e in tree['tree'] if e['type']=='blob' and any(
            e['path'].startswith(s) if s.endswith('/') else e['path']==s for s in dep['include'])]
        if not entries: raise RuntimeError('No source files: '+repo)
        base = (ROOT/dep['destination']).resolve()
        if not base.is_relative_to((ROOT/'vendor').resolve()): raise ValueError('Source destination outside vendor')
        def prepare(entry):
            relative = PurePosixPath(entry['path'])
            if relative.is_absolute() or '..' in relative.parts: raise ValueError('Invalid source path')
            dest = base.joinpath(*relative.parts)
            if dest.exists() and blob_hash(dest.read_bytes())==entry['sha']: return
            if a.verify_only: raise RuntimeError('Missing or modified source: '+str(dest))
            # Do not silently replace developer edits in vendor.
            if dest.exists(): raise RuntimeError('Source differs from lock; inspect before replacing: '+str(dest))
            data = fetch(f'https://raw.githubusercontent.com/{repo}/{commit}/{entry["path"]}')
            if blob_hash(data)!=entry['sha']: raise RuntimeError('Source checksum mismatch: '+entry['path'])
            dest.parent.mkdir(parents=True, exist_ok=True)
            temp = dest.with_name(dest.name+'.download')
            temp.write_bytes(data); temp.replace(dest)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(prepare, entries))
        print(f'{repo}@{commit[:12]}: verified {len(entries)} files', flush=True)

if __name__ == '__main__': main()
