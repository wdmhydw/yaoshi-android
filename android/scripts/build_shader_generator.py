"""Build the Windows host shader generator and snapshot shaders to an ASCII path."""
import argparse
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--vcvars',type=Path,required=True,help='Visual Studio VC/Auxiliary/Build/vcvars64.bat')
    p.add_argument('--work',type=Path,required=True,help='ASCII build directory')
    a=p.parse_args()
    work=a.work.resolve()
    if not str(work).isascii():p.error('--work must be ASCII for the Windows shader tools')
    for value in (str(work),str(a.vcvars.resolve())):
        if any(c in value for c in ('%','!','"','\r','\n')):p.error('Unsupported shell characters in build path')
    source=ROOT/'vendor/moss-native/third_party/ggml/src/ggml-vulkan/vulkan-shaders'
    dest=work/'source'
    shutil.copytree(source,dest,dirs_exist_ok=True)
    exe=work/'vulkan-shaders-gen.exe'
    script=work/'compile-generator.cmd'
    script.write_text('@echo off\ncall "'+str(a.vcvars.resolve())+'" >nul\nif errorlevel 1 exit /b 1\n'+
        'cl /nologo /EHsc /std:c++17 /O2 /Fe:"'+str(exe)+'" /Fo:"'+str(work/'vulkan-shaders-gen.obj')+'" "'+str(dest/'vulkan-shaders-gen.cpp')+'"\nexit /b %errorlevel%\n',encoding='utf8')
    subprocess.run(['cmd.exe','/d','/c',str(script)],check=True)
    print('Generator:',exe)
    print('Shader source:',dest)

if __name__=='__main__':main()
