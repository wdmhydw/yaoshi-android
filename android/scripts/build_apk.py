"""Build and verify the Android APK with the official Android SDK/JDK tools.

No Gradle daemon, downloaded build plugin, system Python, or network is needed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--sdk',type=Path,required=True)
    p.add_argument('--jdk',type=Path)
    p.add_argument('--work',type=Path,help='ASCII-only temporary build directory for aapt2')
    mode=p.add_mutually_exclusive_group()
    mode.add_argument('--release',action='store_true',help='Sign using YAOSHI_STORE_PASSWORD and YAOSHI_KEY_PASSWORD')
    mode.add_argument('--test-install',action='store_true',help='Build a debuggable APK with a separate application ID')
    p.add_argument('--keystore',type=Path)
    p.add_argument('--key-alias',default='yaoshi',help='Release key alias')
    p.add_argument('--lineage',type=Path,help='Optional Android signing certificate rotation lineage')
    a=p.parse_args()
    if a.release and (not a.keystore or not os.environ.get('YAOSHI_STORE_PASSWORD') or not os.environ.get('YAOSHI_KEY_PASSWORD')):
        p.error('--release needs --keystore and both signing password environment variables')
    if a.lineage and not a.release:p.error('--lineage needs --release')
    jdk=a.jdk or a.sdk/'jdk17'
    bt=a.sdk/'build-tools/36.0.0';android=a.sdk/'platforms/android-36/android.jar'
    work=a.work or Path(tempfile.gettempdir())/'moss-android-apk';work.mkdir(parents=True,exist_ok=True)
    out=Path(tempfile.mkdtemp(prefix='build-',dir=work))
    for n in ('classes','generated','dex','assets'): (out/n).mkdir(exist_ok=True)
    def run(args):subprocess.run([str(s) for s in args],check=True)
    original_app=ROOT/'android/app/src/main'
    # Raster provenance sidecars are source documentation, not Android drawable
    # resources. AAPT otherwise invents bogus drawable names for *.webp.json.
    app=out/'src';shutil.copytree(original_app,app,dirs_exist_ok=True,
        ignore=lambda path,names: [n for n in names if n.endswith('.webp.json') or n=='provenance.json'])
    manifest=app/'AndroidManifest.xml'
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    doc=ET.parse(manifest)
    ns='{http://schemas.android.com/apk/res/android}'
    version=doc.getroot().get(ns+'versionName')
    doc.find('application').set(ns+'debuggable','false' if a.release else 'true')
    if a.test_install:
        doc.find('application').set(ns+'label','燿识 · 测试')
    doc.write(manifest,encoding='UTF-8',xml_declaration=True)
    if (app/'assets').is_dir():shutil.copytree(app/'assets',out/'assets',dirs_exist_ok=True)
    source_commit=None
    try:
        repo=Path(subprocess.check_output(['git','-C',str(ROOT),'rev-parse','--show-toplevel'],stderr=subprocess.DEVNULL,text=True).strip())
        if repo.resolve()==ROOT:
            source_commit=subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True,stderr=subprocess.DEVNULL).strip()
    except (OSError,subprocess.CalledProcessError):
        pass
    (out/'assets/build-info.json').write_text(json.dumps({'version':version,'source_commit':source_commit}),encoding='utf8')
    if a.release or (ROOT/'LICENSE').is_file():
        shutil.copyfile(ROOT/'LICENSE',out/'assets/Yaoshi-LICENSE.txt')
    run([bt/'aapt2.exe','compile','--dir',app/'res','-o',out/'resources.zip'])
    shutil.copyfile(ROOT/'android/fixtures/demo-zh.wav',out/'assets/demo-zh.wav')
    for src,name in [(ROOT/'vendor/moss-native/LICENSE','moss-LICENSE.txt'),(ROOT/'vendor/moss-native/third_party/ggml/LICENSE','ggml-LICENSE.txt'),
                     (ROOT/'android/THIRD_PARTY_NOTICES.txt','THIRD_PARTY_NOTICES.txt'),
                     (ROOT/'android/onnxruntime-LICENSE.txt','ONNX-Runtime-LICENSE.txt'),
                     (ROOT/'android/build/cpu/LLVM-NOTICE.txt','LLVM-NOTICE.txt'),
                     (ROOT/'vendor/Vulkan-Headers/LICENSE.md','Vulkan-Headers-LICENSE.md'),
                     (ROOT/'vendor/SPIRV-Headers/LICENSE','SPIRV-Headers-LICENSE.txt')]:
        shutil.copyfile(src,out/'assets'/name)
    if (ROOT/'android/build/opencl/libmoss_opencl.so').is_file():
        shutil.copyfile(ROOT/'vendor/OpenCL-Headers/LICENSE',out/'assets/OpenCL-Headers-LICENSE.txt')
        shutil.copyfile(ROOT/'vendor/opencl-program-cache/LICENSE',out/'assets/OpenCL-cache-LICENSE.txt')
    for package in ('Vulkan-Headers','SPIRV-Headers'):
        for notice in (ROOT/'vendor'/package/'LICENSES').glob('*'):
            if notice.is_file(): shutil.copyfile(notice,out/'assets'/(package+'-'+notice.name))
    package_args=['--rename-manifest-package','win.youhoo.moss.qa'] if a.test_install else []
    run([bt/'aapt2.exe','link','-o',out/'base.apk','--manifest',manifest,'-I',android,*package_args,
         '--java',out/'generated','-A',out/'assets','--auto-add-overlay',out/'resources.zip'])
    javafiles=list((app/'java').rglob('*.java'))+list((out/'generated').rglob('*.java'))
    run([jdk/'bin/javac.exe','-encoding','UTF-8','-source','11','-target','11','-classpath',android,'-d',out/'classes',*javafiles])
    run([jdk/'bin/jar.exe','cf',out/'classes.jar','-C',out/'classes','.'])
    run([jdk/'bin/java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','35','--lib',android,'--output',out/'dex',out/'classes.jar'])
    package=out/'unsigned.apk'
    packaged_native=[]
    with zipfile.ZipFile(out/'base.apk') as base, zipfile.ZipFile(package,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for entry in base.infolist():
            z.writestr(entry,base.read(entry))
        for dex in (out/'dex').glob('*.dex'):z.write(dex,dex.name)
        for mode in ('cpu','vulkan','opencl'):
            binary=ROOT/'android/build'/mode/('libmoss_'+mode+'.so')
            if not binary.is_file():raise RuntimeError('Missing '+mode+' engine for the universal APK')
            z.write(binary,'lib/arm64-v8a/'+binary.name,compress_type=zipfile.ZIP_STORED)
            packaged_native.append(binary)
        probe=ROOT/'android/build/cpu/libmoss_probe.so';z.write(probe,'lib/arm64-v8a/'+probe.name,compress_type=zipfile.ZIP_STORED)
        accel=ROOT/'android/build/cpu/libmoss_accel.so';z.write(accel,'lib/arm64-v8a/'+accel.name,compress_type=zipfile.ZIP_STORED)
        packaged_native += [probe,accel]
        speaker=ROOT/'android/build/speaker/libsherpa-onnx-jni.so'
        lock=json.loads((ROOT/'android/dependencies.lock.json').read_text())['speaker']
        if hashlib.sha256(speaker.read_bytes()).hexdigest()!=lock['native_sha256']: raise RuntimeError('Speaker runtime hash mismatch')
        if hashlib.sha256((original_app/'assets/speaker/3dspeaker.onnx').read_bytes()).hexdigest()!=lock['model_sha256']: raise RuntimeError('Speaker model hash mismatch')
        z.write(speaker,'lib/arm64-v8a/'+speaker.name,compress_type=zipfile.ZIP_STORED)
        packaged_native.append(speaker)
    run([bt/'zipalign.exe','-P','16','-f','4',package,out/'aligned.apk'])
    key=a.keystore or ROOT/'android/build/debug.keystore'
    if a.keystore and not key.is_file():raise FileNotFoundError(key)
    if not key.exists():run([jdk/'bin/keytool.exe','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Android Debug,O=MOSS Development,C=CN','-keyalg','RSA','-keysize','2048','-validity','10000'])
    dest=ROOT/'android/out';dest.mkdir(exist_ok=True)
    apk=dest/(f'Yaoshi-{version}-arm64-v8a.apk' if a.release else 'yaoshi-qa.apk' if a.test_install else 'moss-android-dev.apk')
    signing=['--ks',key,'--ks-key-alias',a.key_alias if a.release else 'androiddebugkey',
             '--ks-pass','env:YAOSHI_STORE_PASSWORD' if a.release else 'pass:android',
             '--key-pass','env:YAOSHI_KEY_PASSWORD' if a.release else 'pass:android']
    if a.lineage:
        signing+=['--lineage',a.lineage,'--rotation-min-sdk-version','33','--v1-signing-enabled','false','--v2-signing-enabled','false']
    run([jdk/'bin/java.exe','-jar',bt/'lib/apksigner.jar','sign',*signing,'--out',apk,out/'aligned.apk'])
    run([jdk/'bin/java.exe','-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
    run([bt/'zipalign.exe','-c','-P','16','4',apk])
    source_hashes={str(f.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(f.read_bytes()).hexdigest() for f in original_app.rglob('*') if f.is_file()}
    native_hashes={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in packaged_native}
    report={'kind':'release' if a.release else 'development','apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bytes':apk.stat().st_size,
            'abi':'arm64-v8a','build_tools':'36.0.0','compile_sdk':36,'min_sdk':35,'sources':source_hashes,'native':native_hashes,
            'dependencies':json.loads((ROOT/'android/dependencies.lock.json').read_text())}
    report['source_commit']=source_commit
    destination='build.json' if not a.release and not a.test_install else apk.stem+'.build.json'
    (dest/destination).write_text(json.dumps(report,indent=2),encoding='utf8')
    print('APK:',apk)

if __name__=='__main__':main()
