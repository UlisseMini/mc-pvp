#!/usr/bin/env python3
"""Real Forge/client integration check using HeadlessMC in the lab sandbox.

Requires the normal launcher to have installed Forge 1.8.9 and Java 8.
Connects the synthetic PracticeCheck account to the local practice server.
Starts a short synthetic boxing duel; does not touch the user's account/settings.
"""
from build import *
from lab_runtime import sandbox_profile

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--optifine',action='store_true');args=parser.parse_args()
    verify_cache()
    mc=Path.home()/'Library/Application Support/minecraft'
    work=BUILD/'smoke';work.mkdir(exist_ok=True)
    dependencies=json.loads((SRC/'smoke-dependencies.lock.json').read_text())
    for name,rec in dependencies.items():
        p=CACHE/name
        if not p.exists():
            data=urllib.request.urlopen(rec['url'],timeout=30).read()
            if hashlib.sha256(data).hexdigest()!=rec['sha256']:raise RuntimeError('Dependency hash mismatch')
            p.write_bytes(data)
        if hashlib.sha256(p.read_bytes()).hexdigest()!=rec['sha256']:raise RuntimeError('Dependency changed: '+name)
    sources=[]
    for p in (ROOT/'build/hmc-source').rglob('*.java'):
        text=p.read_text().replace('var dimensions =','int[] dimensions =').replace('var constructor =','java.lang.reflect.Constructor<?> constructor =')
        out=work/'hmc-source'/p.relative_to(ROOT/'build/hmc-source');out.parent.mkdir(parents=True,exist_ok=True);out.write_text(text);sources.append(out)
    cp=os.pathsep.join(map(str,[ROOT/'.cache/asm.jar',ROOT/'.cache/asm-tree.jar']))
    compile_to(work/'agent-classes',sources+[SRC/'smoke/HeadlessAgent.java'],cp)
    with zipfile.ZipFile(work/'headless-agent.jar','w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: practice.smoke.HeadlessAgent\n\n')
        for p in (work/'agent-classes').rglob('*.class'):z.write(p,str(p.relative_to(work/'agent-classes')))
        for name in ['asm.jar','asm-tree.jar']:
            with zipfile.ZipFile(ROOT/'.cache'/name) as dep:
                for n in dep.namelist():
                    if n.endswith('.class') and not n.endswith('module-info.class'):z.writestr(n,dep.read(n))
        z.write(ROOT/'.cache/HEADLESSMC-LICENSE','HEADLESSMC-LICENSE')
    # Forge 1.8.9 requires ASM 5's permissive descriptor handling. Keep the
    # newer ASM used by HeadlessMC private to the test agent.
    with zipfile.ZipFile(work/'headless-agent.jar') as z:
        mappings=['CL: '+n[:-6]+' practice/shaded/'+n[:-6] for n in z.namelist() if n.startswith('org/objectweb/asm/') and n.endswith('.class')]
    (work/'shade.srg').write_text('\n'.join(mappings))
    toolcp=os.pathsep.join(map(str,[BUILD/'tools',ROOT/'.cache/asm.jar',CACHE/'asm-commons.jar']))
    run([JAVA,'-cp',toolcp,'Remap',work/'shade.srg',work/'headless-shaded.jar',work/'headless-agent.jar'])
    with zipfile.ZipFile(work/'headless-shaded.jar','a') as z:
        z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: practice.smoke.HeadlessAgent\n\n')
        z.write(ROOT/'.cache/HEADLESSMC-LICENSE','HEADLESSMC-LICENSE')
    libraries=json.loads((ROOT/'.cache/classpath.json').read_text())
    cp=os.pathsep.join([str(BUILD/'compile-api.jar'),str(BUILD/'api'),*libraries])
    compile_to(work/'probe-classes',[SRC/'smoke/ForgeProbe.java'],cp)
    jar(work/'probe.jar',work/'probe-classes')
    game=ROOT/'runtime/practice-forge-smoke';game.mkdir(exist_ok=True)
    for d in ['mods','config/pvp-practice','tmp','home']:(game/d).mkdir(parents=True,exist_ok=True)
    optifine=game/'mods/OptiFine_1.8.9_HD_U_L5.jar'
    if args.optifine:
        source=mc/'mods'/optifine.name
        if hashlib.sha256(source.read_bytes()).hexdigest()!='b442bf55e4c703c55f5f48055d79c07eb853fcb76fa88f85587e911af4f2b084':raise RuntimeError('OptiFine does not match the verified official download')
        shutil.copyfile(source,optifine)
    else:optifine.unlink(missing_ok=True)
    shutil.copyfile(BUILD/'practice-bridge.jar',game/'mods/practice-bridge.jar')
    shutil.copyfile(work/'probe.jar',game/'mods/probe.jar')
    shutil.copyfile(BUILD/'practice-module.jar',game/'config/pvp-practice/practice-module.jar')
    shutil.copyfile(SRC/'settings.properties',game/'config/pvp-practice/settings.properties')
    with (game/'config/pvp-practice/settings.properties').open('a') as f:f.write('\narchive.directory='+str(ROOT/'runtime/pov')+'\n')
    (game/'config/splash.properties').write_text('enabled=false\n')
    (game/'options.txt').write_text('renderDistance:2\nmaxFps:30\nsoundCategory_master:0.0\n')
    forge=json.loads(next((mc/'versions/1.8.9-forge1.8.9-11.15.1.2318-1.8.9').glob('*.json')).read_text())
    cp=[str(work/'headless-shaded.jar')]
    extra={'launchwrapper':'launchwrapper.jar','asm-all':'asm-all.jar','lzma':'lzma.jar'}
    for lib in forge['libraries']:
        if lib.get('clientreq') is False:continue
        g,a,v=lib['name'].split(':');p=mc/'libraries'/g.replace('.','/')/a/v/(a+'-'+v+'.jar')
        if a in extra:p=CACHE/extra[a]
        if not p.exists():raise RuntimeError('Missing '+str(p))
        if lib.get('checksums') and hashlib.sha1(p.read_bytes()).hexdigest() not in lib['checksums']:raise RuntimeError('Installed Forge dependency changed: '+str(p))
        cp.append(str(p))
    cp+=libraries;cp.append(str(ROOT/'.cache/client.jar'))
    java=mc/'runtime/jre-legacy/mac-os/jre-legacy/jre.bundle/Contents/Home/bin/java'
    profile=sandbox_profile().read_text().replace(json.dumps(str(JAVA)),json.dumps(str(java)))
    profile=profile.replace('(subpath '+json.dumps(str(ROOT))+')','(subpath '+json.dumps(str(ROOT))+') '+ ' '.join('(subpath '+json.dumps(str(mc/d))+')' for d in ['libraries','runtime','assets']))
    profile=profile.replace('(subpath '+json.dumps(str(ROOT/'runtime'))+')','(subpath '+json.dumps(str(game))+') (subpath '+json.dumps(str(ROOT/'runtime/pov'))+')')
    (game/'sandbox.sb').write_text(profile)
    args=['/usr/bin/sandbox-exec','-f',str(game/'sandbox.sb'),str(java),'-Xmx768M','-XX:-UsePerfData',
          '-Duser.home='+str(game/'home'),'-Djava.io.tmpdir='+str(game/'tmp'),'-Djava.net.preferIPv4Stack=true','-Dhmc.lwjgl.update_sleep=1',
          '-Dlog4j.configurationFile='+str(ROOT/'.cache/server-logging.xml'),'-javaagent:'+str(work/'headless-shaded.jar'),
          '-cp',os.pathsep.join(cp),'net.minecraft.launchwrapper.Launch','--username','PracticeCheck','--version','1.8.9',
          '--gameDir',str(game),'--assetsDir',str(mc/'assets'),'--assetIndex','1.8','--uuid','00000000-0000-0000-0000-000000000007',
          '--accessToken','0','--userProperties','{}','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
          '--server','127.0.0.1','--port','25565']
    log=game/'console.log'
    with log.open('w') as stream:
        process=subprocess.Popen(args,cwd=game,stdout=stream,stderr=subprocess.STDOUT,
            env={'PATH':'/usr/bin:/bin','LANG':'en_US.UTF-8','TMPDIR':str(game/'tmp')+'/'})
        (game/'pid').write_text(str(process.pid))
        print('Isolated Forge client running; log:',log,flush=True)
        try:code=process.wait(timeout=150)
        except subprocess.TimeoutExpired:
            process.kill();process.wait();raise RuntimeError('Forge smoke test timed out; see '+str(log))
    text=log.read_text(errors='replace')
    if code or '[PracticeProbe] ALL CHECKS PASSED' not in text or '[PracticeProbe] FAILED' in text:
        print(text[-12000:]);raise RuntimeError('Forge smoke test failed: '+str(code))
    print('\n'.join(line for line in text.splitlines() if '[PracticeProbe]' in line or '[PracticeBridge]' in line))

if __name__=='__main__':main()
