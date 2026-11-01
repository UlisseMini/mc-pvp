#!/usr/bin/env python3
"""Real Forge localhost-pretend-remote test; no matches or server restart."""
from build import *
import forge_runtime

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=25565);parser.add_argument('--wait-idle-seconds',type=int,default=0);args=parser.parse_args()
    end=time.monotonic()+args.wait_idle_seconds
    while True:
        status=json.loads((ROOT/'runtime/status.json').read_text())
        if not status['matches'] or time.monotonic()>=end:break
        time.sleep(1)
    if time.time()-status['time']/1000>5 or status['matches']:raise RuntimeError('Need fresh idle lab status; no duel may be interrupted')
    game=ROOT/'runtime/practice-remote-smoke';game.mkdir(exist_ok=True);work=BUILD/'remote-smoke';work.mkdir(exist_ok=True)
    for d in ['mods','config/pvp-practice','tmp','home']:(game/d).mkdir(parents=True,exist_ok=True)
    classpath=forge_runtime.stage_dependencies();libraries=json.loads((ROOT/'.cache/classpath.json').read_text())
    compile_to(work/'probe',[SRC/'smoke/RemoteProbe.java'],os.pathsep.join([str(BUILD/'compile-api.jar'),str(BUILD/'api'),*libraries]))
    jar(work/'probe.jar',work/'probe')
    for src,dest in [(BUILD/'practice-bridge.jar',game/'mods/practice-bridge.jar'),(work/'probe.jar',game/'mods/remote-probe.jar'),(BUILD/'practice-module.jar',game/'config/pvp-practice/practice-module.jar'),(SRC/'settings.properties',game/'config/pvp-practice/settings.properties')]:shutil.copyfile(src,dest)
    (game/'config/splash.properties').write_text('enabled=false\n');(game/'options.txt').write_text('renderDistance:2\nmaxFps:30\nsoundCategory_master:0.0\n')
    report=game/'verification.json';report.unlink(missing_ok=True)
    command=['-Xmx512M','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-Dpractice.test.forceRemote=true','-Dpractice.test.output='+str(report),'-Dhmc.lwjgl.update_sleep=5','-Dlog4j.configurationFile='+str(ROOT/'.cache/server-logging.xml'),'-javaagent:'+str(forge_runtime.HEADLESS),'-cp',os.pathsep.join(map(str,classpath)),'net.minecraft.launchwrapper.Launch','--username','PracticeRemote','--version','1.8.9','--gameDir',str(game),'--assetsDir',str(forge_runtime.ASSETS),'--assetIndex','1.8','--uuid','00000000-0000-0000-0000-000000000008','--accessToken','0','--userProperties','{}','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker','--server','127.0.0.1','--port',str(args.port)]
    log=game/'console.log'
    if log.exists():shutil.copyfile(log,game/('console-previous-'+str(time.time_ns())+'.log'))
    with log.open('w')as out:
        process=subprocess.Popen(['nice','-n','10',*forge_runtime.command(command,game)],cwd=game,stdout=out,stderr=subprocess.STDOUT,env={'PATH':'/usr/bin:/bin','LANG':'en_US.UTF-8','TMPDIR':str(game/'tmp')+'/'})
        deadline=time.monotonic()+100
        while True:
            try:code=process.wait(timeout=.5);break
            except subprocess.TimeoutExpired:
                if time.monotonic()>deadline:
                    process.kill();process.wait();raise RuntimeError('Remote smoke timed out; see '+str(log))
    text=log.read_text(errors='replace');print('\n'.join(l for l in text.splitlines()if'[RemoteProbe]'in l))
    if code or'[RemoteProbe] ALL CHECKS PASSED'not in text:print(text[-10000:]);raise RuntimeError('Remote smoke failed; see '+str(log))
    print(report.read_text())
if __name__=='__main__':main()
