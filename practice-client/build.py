#!/usr/bin/env python3
"""Small reproducible Java 8 build; no Gradle, upstream scripts or processors."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, urllib.request, zipfile, csv, io, time

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
def jar(dest, directory, metadata=None, extras=None):
    with zipfile.ZipFile(dest.with_suffix('.tmp'),'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(directory.rglob('*.class')):
            info=zipfile.ZipInfo(str(p.relative_to(directory)),(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,p.read_bytes())
        if metadata:
            info=zipfile.ZipInfo('mcmod.info',(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,json.dumps(metadata))
        for name,data in (extras or {}).items():
            info=zipfile.ZipInfo(name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
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
    api=BUILD/'api';compile_to(api,sorted((SRC/'api').rglob('*.java'))+sorted((ROOT/'src/practice').rglob('*.java')),'')
    core=BUILD/'core';compile_to(core,sorted((SRC/'core').rglob('*.java')),str(api))
    module=BUILD/'module';compile_to(module,sorted((SRC/'module').rglob('*.java')),str(api))
    jar(BUILD/'practice-module.jar',module)
    if not args.module_only:
        cp=os.pathsep.join(map(str,[ROOT/'.cache/asm.jar',CACHE/'asm-commons.jar']))
        tool=BUILD/'tools';compile_to(tool,[SRC/'tools/Remap.java'],cp)
        run([JAVA,'-cp',os.pathsep.join([str(tool),cp]),'Remap',ROOT/'.cache/joined.srg',BUILD/'compile-api.jar',ROOT/'.cache/client.jar',CACHE/'forge.jar'])
        libs=json.loads((ROOT/'.cache/classpath.json').read_text())
        cp=os.pathsep.join([str(api),str(core),str(BUILD/'compile-api.jar'),str(ROOT/'.cache/asm.jar'),str(ROOT/'.cache/asm-tree.jar'),str(CACHE/'launchwrapper.jar'),*libs])
        bot_sources=[ROOT/'src/lab'/f'{name}.java' for name in json.loads((SRC/'bot-sources.json').read_text())]
        shim=BUILD/'shim';compile_to(shim,sorted((SRC/'shim').rglob('*.java'))+bot_sources,cp)
        label_checks=BUILD/'label-checks';label_cp=os.pathsep.join([str(shim),cp])
        compile_to(label_checks,[SRC/'tools/PlayerLabelsCheck.java',SRC/'tools/ClockHooksCheck.java',SRC/'tools/RemoteSafetyCheck.java',SRC/'tools/CombatDirectionCheck.java',SRC/'tools/ItemUseAnimationCheck.java'],label_cp)
        run([JAVA,'-ea','-cp',os.pathsep.join([str(label_checks),label_cp]),'PlayerLabelsCheck',ROOT/'.cache/client.jar',BUILD/'compile-api.jar'])
        run([JAVA,'-ea','-cp',os.pathsep.join([str(label_checks),label_cp]),'ClockHooksCheck',ROOT/'.cache/client.jar',BUILD/'compile-api.jar'])
        run([JAVA,'-ea','-cp',os.pathsep.join([str(label_checks),label_cp]),'practice.bridge.RemoteSafetyCheck',shim])
        run([JAVA,'-ea','-cp',os.pathsep.join([str(label_checks),label_cp]),'practice.bridge.CombatDirectionCheck'])
        run([JAVA,'-ea','-cp',os.pathsep.join([str(label_checks),label_cp]),'ItemUseAnimationCheck',ROOT/'.cache/client.jar',BUILD/'compile-api.jar'])
        for folder in (api,core):
            shutil.copytree(folder,shim,dirs_exist_ok=True)
        with zipfile.ZipFile(ROOT/'.cache/mcp-names.zip') as names:
            field_names='\n'.join(r['searge']+'='+r['name'] for r in csv.DictReader(io.StringIO(names.read('fields.csv').decode())))
            names_by_srg={r['searge']:r['name'] for entry in ('fields.csv','methods.csv') for r in csv.DictReader(io.StringIO(names.read(entry).decode()))}
        bot_mappings=[]
        for line in (ROOT/'.cache/joined.srg').read_text().splitlines():
            p=line.split()
            if p[0]=='CL:':
                bot_mappings.append(f'O.{p[2]}={p[1]}')
            elif p[0]=='FD:':
                owner,name=p[2].rsplit('/',1);bot_mappings.append(f'F.{owner}.{name}={names_by_srg.get(name,name)}')
            elif p[0]=='MD:':
                owner,name=p[3].rsplit('/',1);bot_mappings.append(f'M.{owner}.{name}{p[4]}={names_by_srg.get(name,name)}')
        manifest='Manifest-Version: 1.0\nFMLCorePlugin: practice.bridge.PracticeLoadingPlugin\nFMLCorePluginContainsFMLMod: true\n\n'
        jar(BUILD/'practice-bridge.jar',shim,[{'modid':'practicebridge','name':'PvP Practice Bridge','description':'Local, reloadable practice HUD and telemetry.','version':'0.4.0','mcversion':'1.8.9','authorList':['PvP Lab']}],{'META-INF/MANIFEST.MF':manifest,'practice/field-names.properties':field_names,'lab/mappings.properties':'\n'.join(bot_mappings)})
    tests=BUILD/'tests';compile_to(tests,sorted((SRC/'tests').rglob('*.java')),os.pathsep.join(map(str,[api,core,module])))
    run([JAVA,'-ea','-cp',os.pathsep.join(map(str,[api,core,tests])),'practice.tests.Check',BUILD/'practice-module.jar'])
    run([JAVA,'-ea','-cp',os.pathsep.join(map(str,[api,core,tests])),'practice.tests.CombatCoachChecks'])
    if args.install:
        if not args.module_only:
            backup=args.game_dir/'settings-backups'/('practice-bridge-'+str(time.time_ns()))
            for old in (args.game_dir/'mods').glob('practice-bridge*.jar'):
                backup.mkdir(parents=True,exist_ok=True);shutil.copy2(old,backup/old.name)
            install(BUILD/'practice-bridge.jar',args.game_dir/'mods/practice-bridge.jar')
            for old in (args.game_dir/'mods').glob('practice-bridge-*.jar'):old.unlink()
        module_name='practice-module.jar' if args.module_only or not (args.game_dir/'config/pvp-practice/practice-module.jar').exists() else 'practice-module-next.jar'
        install(BUILD/'practice-module.jar',args.game_dir/'config/pvp-practice'/module_name)
        # A compatible hot update must not revert to an older staged module on restart.
        pending=args.game_dir/'config/pvp-practice/practice-module-next.jar'
        if args.module_only and pending.exists():install(BUILD/'practice-module.jar',pending)
        if module_name.endswith('-next.jar'):print('Module staged for next Minecraft start; the currently running module remains active.')
        cfg=args.game_dir/'config/pvp-practice/settings.properties'
        if not cfg.exists():install(SRC/'settings.properties',cfg)
        if 'archive.directory=' not in cfg.read_text():
            with cfg.open('a') as f:f.write('\narchive.directory='+str(ROOT/'runtime/pov')+'\n')
        print('Installed into',args.game_dir)
    print('Built and tested:',BUILD)

if __name__=='__main__':main()
