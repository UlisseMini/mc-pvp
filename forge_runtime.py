"""Pinned Java 8/Forge launch support shared by production bots and native smoke tests.

Stages only allowlisted libraries. It never copies account stores, user options, or mods.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import uuid
import zipfile

from lab import ROOT, CACHE, BUILD, RUNTIME, JAVA, verify_cache

MC = Path.home() / 'Library/Application Support/minecraft'
JAVA8 = MC / 'runtime/jre-legacy/mac-os/jre-legacy/jre.bundle/Contents/Home/bin/java'
ASSETS = MC / 'assets'
BRIDGE = BUILD / 'practice-client/practice-bridge.jar'
MODULE = BUILD / 'practice-client/practice-module.jar'
HEADLESS = BUILD / 'forge/headless-shaded.jar'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_headless():
    verify_cache()
    spec = importlib.util.spec_from_file_location('practice_build', ROOT/'practice-client/build.py')
    builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
    work=BUILD/'forge';work.mkdir(parents=True,exist_ok=True)
    sources=[]
    for p in (BUILD/'hmc-source').rglob('*.java'):
        text=p.read_text().replace('var dimensions =','int[] dimensions =').replace('var constructor =','java.lang.reflect.Constructor<?> constructor =')
        out=work/'hmc-source'/p.relative_to(BUILD/'hmc-source');out.parent.mkdir(parents=True,exist_ok=True);out.write_text(text);sources.append(out)
    if not sources:raise RuntimeError('Run lab.py prepare to generate verified HeadlessMC sources')
    cp=os.pathsep.join(str(CACHE/name) for name in ('asm.jar','asm-tree.jar'))
    builder.compile_to(work/'agent-classes',sources+[ROOT/'practice-client/headless/practice/headless/HeadlessAgent.java',ROOT/'src/security/LegacyLoggingTransformer.java'],cp)
    with zipfile.ZipFile(work/'headless-agent.jar','w',zipfile.ZIP_DEFLATED) as out:
        for p in (work/'agent-classes').rglob('*.class'):out.write(p,str(p.relative_to(work/'agent-classes')))
        for name in ('asm.jar','asm-tree.jar'):
            with zipfile.ZipFile(CACHE/name) as dep:
                for n in dep.namelist():
                    if n.endswith('.class') and not n.endswith('module-info.class'):out.writestr(n,dep.read(n))
    with zipfile.ZipFile(work/'headless-agent.jar') as dep:
        mappings=['CL: '+n[:-6]+' practice/shaded/'+n[:-6] for n in dep.namelist() if n.startswith('org/objectweb/asm/') and n.endswith('.class')]
    (work/'shade.srg').write_text('\n'.join(mappings))
    toolcp=os.pathsep.join(str(p) for p in (BUILD/'practice-client/tools',CACHE/'asm.jar',CACHE/'practice-client/asm-commons.jar'))
    builder.run([JAVA,'-cp',toolcp,'Remap',work/'shade.srg',HEADLESS,work/'headless-agent.jar'])
    with zipfile.ZipFile(HEADLESS,'a') as out:
        out.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: practice.headless.HeadlessAgent\n\n')
        out.write(CACHE/'HEADLESSMC-LICENSE','HEADLESSMC-LICENSE')
    probe_source=work/'probe-src';probe_source.mkdir(exist_ok=True)
    for name in ('SandboxProbe','LoggingProbe'):
        text=(ROOT/f'src/lab/{name}.java').read_text().replace('Files.readString(canary)','Files.readAllBytes(canary)').replace('Files.writeString(canary.resolveSibling(canary.getFileName()+".write"),"probe")','Files.write(canary.resolveSibling(canary.getFileName()+".write"),"probe".getBytes("UTF-8"))').replace('Files.writeString(allowed,"sandbox allowed write")','Files.write(allowed,"sandbox allowed write".getBytes("UTF-8"))')
        (probe_source/(name+'.java')).write_text(text)
    builder.compile_to(work/'probes',list(probe_source.glob('*.java')),'')
    return HEADLESS


def stage_dependencies():
    verify_cache()
    if not JAVA8.is_file():raise RuntimeError('Install the official launcher Java 8 runtime (Forge 1.8.9) first')
    if not BRIDGE.exists() or not MODULE.exists():raise RuntimeError('Run python3 practice-client/build.py first')
    locked=json.loads((ROOT/'practice-client/forge-runtime.lock.json').read_text())
    dest=CACHE/'forge-runtime';dest.mkdir(exist_ok=True)
    paths=[]
    for coordinate,record in locked.items():
        source=ROOT/record['cached_path'] if record['cached_path'] else MC/'libraries'/record['minecraft_path']
        target=dest/record['minecraft_path'];target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists():
            if not source.is_file() or digest(source)!=record['sha256']:raise RuntimeError('Missing or changed verified Forge library: '+coordinate)
            shutil.copyfile(source,target)
        if digest(target)!=record['sha256']:raise RuntimeError('Staged Forge library changed: '+coordinate)
        paths.append(target)
    if not HEADLESS.exists():build_headless()
    return [HEADLESS,*paths,*map(Path,json.loads((CACHE/'classpath.json').read_text())),CACHE/'client.jar']


def sandbox_profile(game=None):
    # Reuse restrictions while explicitly allowing only the selected Java 8 runtime/assets.
    from lab_runtime import sandbox_profile as base_profile
    profile=base_profile().read_text().replace(json.dumps(str(JAVA)),json.dumps(str(JAVA8)))
    profile=profile.replace('(subpath '+json.dumps(str(ROOT))+')','(subpath '+json.dumps(str(ROOT))+') (subpath '+json.dumps(str(JAVA8.parent.parent))+') (subpath '+json.dumps(str(ASSETS))+')')
    if game is not None:
        profile=profile.replace('(subpath '+json.dumps(str(RUNTIME))+')','(subpath '+json.dumps(str(game))+') (subpath '+json.dumps(str(RUNTIME/'pov'))+')')
    path=(game or RUNTIME)/'forge-sandbox.sb';path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp');temporary.write_text(profile);temporary.replace(path)
    return path


def command(args,game=None):
    return ['/usr/bin/sandbox-exec','-f',str(sandbox_profile(game)),str(JAVA8),'-XX:-UsePerfData',
        '-Djava.net.preferIPv4Stack=true','-Duser.home='+str((game or RUNTIME)/'home'),'-Djava.io.tmpdir='+str((game or RUNTIME)/'tmp'),*map(str,args)]


def verify_security():
    import tempfile
    from lab_runtime import clean_environment
    stage_dependencies()
    fd,path=tempfile.mkstemp(prefix='pvp-forge-canary-');os.close(fd);canary=Path(path);canary.write_text('non-sensitive sandbox probe')
    results={}
    try:
        args=['-cp',str(BUILD/'forge/probes'),'lab.SandboxProbe',str(canary),str(RUNTIME/'tmp/forge-probe.txt')]
        for label,arguments in [('sandbox',args),('logging',['-javaagent:'+str(HEADLESS),'-cp',str(BUILD/'forge/probes')+os.pathsep+str(CACHE/'server.jar'),'lab.LoggingProbe'])]:
            result=subprocess.run(command(arguments),capture_output=True,text=True,timeout=30,env=clean_environment())
            if result.returncode:raise RuntimeError('Forge '+label+' verification failed: '+result.stdout+result.stderr)
            results[label]={'passed':True,'output':result.stdout.strip()}
    finally:canary.unlink(missing_ok=True);Path(str(canary)+'.write').unlink(missing_ok=True)
    (RUNTIME/'forge-security-verification.json').write_text(json.dumps(results,indent=2))
    return results


def bot_command(slot,port,frame_sleep_ms,classpath):
    game=RUNTIME/f'bots/{slot}'
    for d in ('mods','config/pvp-practice','tmp','home'):(game/d).mkdir(parents=True,exist_ok=True)
    allowed={'practice-bridge.jar'}
    unexpected=[p.name for p in (game/'mods').glob('*.jar') if p.name not in allowed]
    if unexpected:raise RuntimeError('Unexpected bot mods; refusing launch: '+', '.join(unexpected))
    shutil.copyfile(BRIDGE,game/'mods/practice-bridge.jar')
    shutil.copyfile(MODULE,game/'config/pvp-practice/practice-module.jar')
    (game/'config/pvp-practice/practice-module-next.jar').unlink(missing_ok=True)
    (game/'config/pvp-practice/settings.properties').write_text((ROOT/'practice-client/settings.properties').read_text()+'\narchive.directory='+str(RUNTIME/'pov')+'\nsprint.enabled=false\nrecord.enabled=false\n')
    (game/'config/splash.properties').write_text('enabled=false\n')
    name=f'LabSlot{slot}'
    metadata={'role':'bot','backend':'forge','slot':slot,'minecraft':'1.8.9','forge':'11.15.1.2318','bridge_sha256':digest(BRIDGE),'module_sha256':digest(MODULE),'headless_sha256':digest(HEADLESS),'java_sha256':digest(JAVA8)}
    (game/'client-runtime.json').write_text(json.dumps(metadata,indent=2))
    (game/'runtime.properties').write_text(''.join(str(k)+'='+str(v)+'\n' for k,v in metadata.items()))
    args=['-Xms128M','-Xmx768M','-XX:ParallelGCThreads=2','-XX:ConcGCThreads=1','-Dlab.root='+str(ROOT),'-Dlab.role=client','-Dlab.backend=forge',f'-Dlab.slot={slot}',f'-Dlab.port={port}',
        '-Dpractice.role=bot','-Dpractice.bridge_sha256='+metadata['bridge_sha256'],'-Dpractice.module_sha256='+metadata['module_sha256'],
        f'-Dhmc.lwjgl.update_sleep={frame_sleep_ms}','-Dlog4j.configurationFile='+str(CACHE/'server-logging.xml'),'-javaagent:'+str(HEADLESS),
        '-cp',os.pathsep.join(map(str,classpath)),'net.minecraft.launchwrapper.Launch','--username',name,'--version','1.8.9',
        '--gameDir',str(game),'--assetsDir',str(ASSETS),'--assetIndex','1.8','--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,name).hex,
        '--accessToken','0','--userProperties','{}','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
        '--width','320','--height','240']
    return command(args,game)


if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','build-headless','verify']);args=p.parse_args()
    if args.action=='build-headless':print(build_headless())
    elif args.action=='prepare':print('Verified Forge classpath:',len(stage_dependencies()),'entries')
    else:print(json.dumps(verify_security(),indent=2))
