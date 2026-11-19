#!/usr/bin/env python3
"""Real Forge/client integration check using HeadlessMC in the lab sandbox.

Requires the normal launcher to have installed Forge 1.8.9 and Java 8.
Connects the synthetic PracticeCheck account to the local practice server.
Starts a short synthetic boxing duel; does not touch the user's account/settings.
"""
from build import *
import forge_runtime

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--optifine',action='store_true');parser.add_argument('--replay-controls',action='store_true',help='Exercise the replay hotbar on the recorded m2151 exchange only');parser.add_argument('--port',type=int,default=25565);args=parser.parse_args();server_port=args.port
    replay_controls=args.replay_controls
    verify_cache()
    mc=Path.home()/'Library/Application Support/minecraft'
    work=BUILD/'smoke';work.mkdir(exist_ok=True)
    forge_classpath=forge_runtime.stage_dependencies()
    libraries=json.loads((ROOT/'.cache/classpath.json').read_text())
    cp=os.pathsep.join([str(BUILD/'compile-api.jar'),str(BUILD/'api'),*libraries])
    compile_to(work/'probe-classes',[SRC/'smoke/ForgeProbe.java',SRC/'smoke/ServerReplayControlChecks.java'],cp)
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
    args=['-Xmx768M','-Dhmc.lwjgl.update_sleep=1',
          '-Dlog4j.configurationFile='+str(ROOT/'.cache/server-logging.xml'),'-javaagent:'+str(forge_runtime.HEADLESS),
          '-cp',os.pathsep.join(map(str,forge_classpath)),'net.minecraft.launchwrapper.Launch','--username','PracticeCheck','--version','1.8.9',
          '--gameDir',str(game),'--assetsDir',str(forge_runtime.ASSETS),'--assetIndex','1.8','--uuid','00000000-0000-0000-0000-000000000007',
          '--accessToken','0','--userProperties','{}','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
          '--server','127.0.0.1','--port',str(server_port)]
    if replay_controls:args.insert(0,'-Dpractice.probe.replay_controls=true')
    args=forge_runtime.command(args,game)
    log=game/'console.log'
    with log.open('w') as stream:
        process=subprocess.Popen(args,cwd=game,stdout=stream,stderr=subprocess.STDOUT,
            env={'PATH':'/usr/bin:/bin','LANG':'en_US.UTF-8','TMPDIR':str(game/'tmp')+'/'})
        (game/'pid').write_text(str(process.pid))
        print('Isolated Forge client running; log:',log,flush=True)
        try:code=process.wait(timeout=210)
        except subprocess.TimeoutExpired:
            process.kill();process.wait();raise RuntimeError('Forge smoke test timed out; see '+str(log))
    text=log.read_text(errors='replace')
    if code or '[PracticeProbe] ALL CHECKS PASSED' not in text or '[PracticeProbe] FAILED' in text:
        print(text[-12000:]);raise RuntimeError('Forge smoke test failed: '+str(code))
    print('\n'.join(line for line in text.splitlines() if '[PracticeProbe]' in line or '[PracticeBridge]' in line))

if __name__=='__main__':main()
