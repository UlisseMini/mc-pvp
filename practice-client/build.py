#!/usr/bin/env python3
"""Small reproducible Java 8 build; no Gradle, upstream scripts or processors."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, urllib.request, zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from lab import JAVA, JAVAC, verify_cache
SRC=ROOT/'practice-client'
BUILD=ROOT/'build/practice-client'
CACHE=ROOT/'.cache/practice-client'

def run(args): subprocess.run(list(map(str,args)),check=True)
def compile_to(dest, sources, cp):
    if dest.exists():shutil.rmtree(dest)
    dest.mkdir(parents=True)
    run([JAVAC,'--release','8','-proc:none','-encoding','UTF-8','-cp',cp,'-d',dest,*sources])
def jar(dest, directory, metadata=None):
    with zipfile.ZipFile(dest.with_suffix('.tmp'),'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(directory.rglob('*.class')):
            info=zipfile.ZipInfo(str(p.relative_to(directory)),(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,p.read_bytes())
        if metadata:
            info=zipfile.ZipInfo('mcmod.info',(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,json.dumps(metadata))
    dest.with_suffix('.tmp').replace(dest)
def install(src,dest):
    dest.parent.mkdir(parents=True,exist_ok=True)
    tmp=dest.with_suffix('.tmp');shutil.copyfile(src,tmp);tmp.replace(dest)
    assert hashlib.sha256(src.read_bytes()).digest()==hashlib.sha256(dest.read_bytes()).digest()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--install',action='store_true');parser.add_argument('--module-only',action='store_true')
    parser.add_argument('--game-dir',type=Path,default=Path.home()/'Library/Application Support/minecraft')
    args=parser.parse_args();BUILD.mkdir(parents=True,exist_ok=True);CACHE.mkdir(parents=True,exist_ok=True)
    verify_cache()
    for name,rec in json.loads((SRC/'dependencies.lock.json').read_text()).items():
        p=CACHE/name
        if not p.exists():
            data=urllib.request.urlopen(rec['url'],timeout=60).read()
            if hashlib.sha256(data).hexdigest()!=rec['sha256']:raise RuntimeError('Download hash mismatch: '+name)
            p.write_bytes(data)
        if hashlib.sha256(p.read_bytes()).hexdigest()!=rec['sha256']:raise RuntimeError('Cached hash mismatch: '+name)
    api=BUILD/'api';compile_to(api,sorted((SRC/'api').rglob('*.java')),'')
    core=BUILD/'core';compile_to(core,sorted((SRC/'core').rglob('*.java')),str(api))
    module=BUILD/'module';compile_to(module,sorted((SRC/'module').rglob('*.java')),str(api))
    jar(BUILD/'practice-module.jar',module)
    if not args.module_only:
        cp=os.pathsep.join(map(str,[ROOT/'.cache/asm.jar',CACHE/'asm-commons.jar']))
        tool=BUILD/'tools';compile_to(tool,[SRC/'tools/Remap.java'],cp)
        run([JAVA,'-cp',os.pathsep.join([str(tool),cp]),'Remap',ROOT/'.cache/joined.srg',BUILD/'compile-api.jar',ROOT/'.cache/client.jar',CACHE/'forge.jar'])
        libs=json.loads((ROOT/'.cache/classpath.json').read_text())
        cp=os.pathsep.join([str(api),str(core),str(BUILD/'compile-api.jar'),*libs])
        shim=BUILD/'shim';compile_to(shim,sorted((SRC/'shim').rglob('*.java')),cp)
        for folder in (api,core):
            shutil.copytree(folder,shim,dirs_exist_ok=True)
        jar(BUILD/'practice-bridge-0.1.0.jar',shim,[{'modid':'practicebridge','name':'PvP Practice Bridge','description':'Local, reloadable practice HUD and telemetry.','version':'0.1.0','mcversion':'1.8.9','authorList':['PvP Lab']}])
    tests=BUILD/'tests';compile_to(tests,sorted((SRC/'tests').rglob('*.java')),os.pathsep.join(map(str,[api,core,module])))
    run([JAVA,'-ea','-cp',os.pathsep.join(map(str,[api,core,tests])),'practice.tests.Check',BUILD/'practice-module.jar'])
    if args.install:
        if not args.module_only:install(BUILD/'practice-bridge-0.1.0.jar',args.game_dir/'mods/practice-bridge-0.1.0.jar')
        install(BUILD/'practice-module.jar',args.game_dir/'config/pvp-practice/practice-module.jar')
        cfg=args.game_dir/'config/pvp-practice/settings.properties'
        if not cfg.exists():install(SRC/'settings.properties',cfg)
        print('Installed into',args.game_dir)
    print('Built and tested:',BUILD)

if __name__=='__main__':main()
