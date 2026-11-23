#!/usr/bin/env python3
"""Passive pre-PLAY remote capture, then socket-free native replay validation.
Never starts a duel, mutates existing players, or restarts the server/client.
"""
from build import *
import forge_runtime,gzip

def graphical_jvm_args(work, game):
    """Prepare the pinned, real OpenGL launcher used for offline exports."""
    cp = os.pathsep.join(str(ROOT / '.cache' / name) for name in ('asm.jar', 'asm-tree.jar'))
    compile_to(work / 'graphics-agent', [SRC / 'smoke/ReplayGraphicsAgent.java',
               ROOT / 'src/security/LegacyLoggingTransformer.java'], cp)
    agent = work / 'graphics-agent.jar'
    jar(agent, work / 'graphics-agent', extras={'META-INF/MANIFEST.MF':
        'Manifest-Version: 1.0\nPremain-Class: practice.smoke.ReplayGraphicsAgent\n\n'})
    native_source = forge_runtime.MC / 'bin/455edb6b1454a7f3243f37b5f240f69e1b0ce4fa'
    natives = game / 'natives'
    natives.mkdir(exist_ok=True)
    expected = {
        'liblwjgl.dylib': '0779bcc14f6bb8a78806650a7cfe20776364c7cc00738bc06e1094e353eb14dd',
        'openal.dylib': 'd845fab22fd58425deafbcb1a552633b4a62bd5dfa27b9263c050d91a3fad8c0',
        'libtwitchsdk.dylib': 'a69cd07a16bdff24b688f47013fd08d2af95e8e29f948ba8c870f06f9939ba68',
    }
    for name, digest in expected.items():
        source = native_source / name
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise RuntimeError('Pinned graphical native mismatch: ' + name)
        shutil.copyfile(source, natives / name)
    return ['-XstartOnFirstThread', '-Djava.library.path=' + str(natives), '-javaagent:' + str(agent)]


def graphical_classpath(classpath):
    return [p for p in classpath if p != forge_runtime.HEADLESS and '2.9.2-nightly' not in str(p)]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--reuse-remote',type=Path);parser.add_argument('--sumo-match');parser.add_argument('--cached-terrain-match');parser.add_argument('--appearance-match');parser.add_argument('--ledger-practice');parser.add_argument('--ledger-sumo');parser.add_argument('--graphical',action='store_true');parser.add_argument('--port',type=int,default=25565);parser.add_argument('--wait-idle-seconds',type=int,default=180);args=parser.parse_args()
    end=time.monotonic()+args.wait_idle_seconds
    while not (args.reuse_remote or args.graphical):
        status=json.loads((ROOT/'runtime/status.json').read_text())
        if not status['matches']or time.monotonic()>=end:break
        time.sleep(1)
    if not (args.reuse_remote or args.graphical) and(time.time()-status['time']/1000>5 or status['matches']):raise RuntimeError('Need fresh initial idle status; no duel may be interrupted')
    archive=ROOT/'runtime/pov/m1395'
    with gzip.open(next(archive.glob('a-client-*-events.jsonl.gz')),'rt')as f:lab=json.loads(next(f))['metadata']['full_session_capture']
    partial=next((ROOT/'runtime/pov/r87ebc0e3ef8b428188969d1df4721c41').glob('a-client-*-events.jsonl.gz'))
    game=ROOT/('runtime/practice-replay-graphics'if args.graphical else'runtime/practice-replay-smoke');game.mkdir(exist_ok=True);work=BUILD/'replay-smoke';work.mkdir(exist_ok=True)
    for d in ['mods','config/pvp-practice','tmp','home']:(game/d).mkdir(parents=True,exist_ok=True)
    classpath=forge_runtime.stage_dependencies();libraries=json.loads((ROOT/'.cache/classpath.json').read_text())
    compile_to(work/'probe',[SRC/'smoke/OfflineReplayProbe.java',SRC/'smoke/ReplayGraphics.java'],os.pathsep.join([str(BUILD/'compile-api.jar'),str(BUILD/'api'),str(BUILD/'core'),*libraries]));jar(work/'probe.jar',work/'probe')
    for src,dest in [(BUILD/'practice-bridge.jar',game/'mods/practice-bridge.jar'),(work/'probe.jar',game/'mods/replay-probe.jar'),(BUILD/'practice-module.jar',game/'config/pvp-practice/practice-module.jar'),(SRC/'settings.properties',game/'config/pvp-practice/settings.properties')]:shutil.copyfile(src,dest)
    with(game/'config/pvp-practice/settings.properties').open('a')as f:f.write('\narchive.directory='+str(ROOT/'runtime/pov')+'\nreach.enabled=true\n')
    (game/'config/splash.properties').write_text('enabled=false\n');(game/'options.txt').write_text('renderDistance:2\nmaxFps:30\nsoundCategory_master:0.0\n')
    report=game/'verification.json';report.unlink(missing_ok=True)
    if args.graphical:classpath=graphical_classpath(classpath)
    command=['-Xmx512M','-XX:ParallelGCThreads=1','-XX:ConcGCThreads=1','-Dpractice.test.forceRemote=true','-Dpractice.test.output='+str(report),'-Dpractice.test.labCapture='+lab,'-Dpractice.test.labArchive='+str(archive),'-Dpractice.test.partialCapture='+str(partial),'-Dhmc.lwjgl.update_sleep=5','-Dlog4j.configurationFile='+str(ROOT/'.cache/server-logging.xml'),'-javaagent:'+str(forge_runtime.HEADLESS),'-cp',os.pathsep.join(map(str,classpath)),'net.minecraft.launchwrapper.Launch','--username','PracticeReplay','--version','1.8.9','--gameDir',str(game),'--assetsDir',str(forge_runtime.ASSETS),'--assetIndex','1.8','--uuid','00000000-0000-0000-0000-000000000009','--accessToken','0','--userProperties','{}','--userType','legacy','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker','--server','127.0.0.1','--port',str(args.port)]
    if args.reuse_remote or args.graphical:
        command=command[:-4]
        if args.reuse_remote and not args.graphical:command.insert(0,'-Dpractice.test.reuseRemote='+str(args.reuse_remote.resolve()))
    if args.sumo_match:command.insert(0,'-Dpractice.test.sumoMatch='+args.sumo_match)
    if args.cached_terrain_match:command.insert(0,'-Dpractice.test.cachedTerrainMatch='+args.cached_terrain_match)
    if args.appearance_match:command.insert(0,'-Dpractice.test.appearanceMatch='+args.appearance_match)
    if args.ledger_practice:command.insert(0,'-Dpractice.test.ledgerPractice='+args.ledger_practice)
    if args.ledger_sumo:command.insert(0,'-Dpractice.test.ledgerSumo='+args.ledger_sumo)
    if args.graphical:
        command=[a for a in command if not a.startswith('-javaagent:')]
        command[:0]=[*graphical_jvm_args(work,game),'-Dpractice.test.graphical=true']
        command+=['--width','960','--height','640']
    log=game/'console.log'
    if log.exists():shutil.copyfile(log,game/('console-previous-'+str(time.time_ns())+'.log'))
    with log.open('w')as out:
        process=subprocess.Popen(['nice','-n','10',*forge_runtime.command(command,game)],cwd=game,stdout=out,stderr=subprocess.STDOUT,env={'PATH':'/usr/bin:/bin','LANG':'en_US.UTF-8','TMPDIR':str(game/'tmp')+'/'})
        try:code=process.wait(timeout=180)
        except subprocess.TimeoutExpired:process.kill();process.wait();raise RuntimeError('Replay smoke timed out; see '+str(log))
    text=log.read_text(errors='replace');print('\n'.join(l for l in text.splitlines()if'[OfflineProbe]'in l))
    if code or('[OfflineProbe] GRAPHICAL SCREENSHOT SAVED'if args.graphical else'[OfflineProbe] ALL CHECKS PASSED')not in text:print(text[-14000:]);raise RuntimeError('Replay smoke failed; see '+str(log))
    print(str(game/'screenshots/offline-replay.png')if args.graphical else report.read_text())
if __name__=='__main__':main()
