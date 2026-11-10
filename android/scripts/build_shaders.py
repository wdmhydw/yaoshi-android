"""Generate ggml Vulkan shaders using a previously compiled host generator."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--generator',type=Path,required=True)
    p.add_argument('--glslc',type=Path,required=True)
    p.add_argument('--source',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True)
    digest=hashlib.sha256()
    for source in sorted(a.source.iterdir()):
        if source.is_file():digest.update(source.name.encode());digest.update(source.read_bytes())
    digest.update(a.generator.read_bytes());digest.update(a.glslc.read_bytes())
    fingerprint=digest.hexdigest()
    stamp=a.output/'inputs.sha256'
    reuse=stamp.exists() and stamp.read_text()==fingerprint
    base=[str(a.generator),'--glslc',str(a.glslc),'--output-dir',str(a.output/'spv'),
          '--target-hpp',str(a.output/'ggml-vulkan-shaders.hpp')]
    subprocess.run(base,check=True)
    for source in sorted(a.source.glob('*.comp')):
        target=a.output/(source.name+'.cpp')
        if target.exists() and reuse: continue
        print('Shader',source.name,flush=True)
        subprocess.run([*base,'--source',str(source),'--target-cpp',str(target)],check=True)
    stamp.write_text(fingerprint)

if __name__=='__main__': main()
