"""Sandboxed JVM supervision and ordered TCP latency injection. Standard library only."""
import asyncio
import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import uuid

from lab import ROOT, CACHE, BUILD, RUNTIME, JAVA, build, verify_cache

BOT_SLOTS=8


def allowed_console_command(command):
    # Keep the existing local inbox restricted to match controls and the
    # explicit administration operations needed for this practice server.
    if not isinstance(command,str) or not command or not all(c.isalnum() or c in '-_ ./' for c in command):
        return False
    return command.startswith(('lab match ','lab diamond ','lab diamond_box ','lab rod_diamond ','lab sumo ','lab ping ','lab scenario ')) or bool(
        re.fullmatch(r'(?:op|deop) [A-Za-z0-9_]{1,16}|weather clear(?: 1000000)?',command))


def clean_environment():
    return {'PATH':'/usr/bin:/bin','HOME':str(RUNTIME/'home'),
            'TMPDIR':str(RUNTIME/'tmp')+'/', 'LANG':'en_US.UTF-8'}


def sandbox_profile():
    RUNTIME.mkdir(exist_ok=True)
    (RUNTIME/'tmp').mkdir(exist_ok=True)
    (RUNTIME/'home').mkdir(exist_ok=True)
    # No user-home read rule. The project is the only readable user directory.
    profile='''(version 1)
(allow default)
(deny file-read-data (require-not (require-any
 (literal "/")
 (subpath "/System") (subpath "/usr") (subpath "/bin") (subpath "/sbin")
 (subpath "/opt/homebrew") (subpath "/Library/Java") (subpath "/Library/Apple")
 (subpath "/private/etc") (subpath "/private/var/db")
 (literal "/dev/random") (literal "/dev/urandom") (literal "/dev/null")
 (literal "/dev/dtracehelper") (literal "/dev/zero")
 (subpath PROJECT))))
(deny file-write* (require-not (require-any (subpath WRITABLE) (literal "/dev/null"))))
(deny process-exec (require-not (literal JAVABINARY)))
(deny network-bind (require-not (local ip "localhost:*")))
(deny network-inbound (require-not (local ip "localhost:*")))
(deny network-outbound (require-not (remote ip "localhost:*")))
'''.replace('PROJECT',json.dumps(str(ROOT))).replace('WRITABLE',json.dumps(str(RUNTIME)))
    profile=profile.replace('JAVABINARY',json.dumps(str(JAVA)))
    path=RUNTIME/'sandbox.sb'
    if not path.exists() or path.read_text()!=profile:
        # Several JVMs start together. Never truncate the profile while an
        # already launched sandbox-exec process is opening it.
        temporary=path.with_name('sandbox-'+uuid.uuid4().hex+'.sb')
        try:temporary.write_text(profile);temporary.replace(path)
        finally:temporary.unlink(missing_ok=True)
    return path


def sandbox_java(args):
    return ['/usr/bin/sandbox-exec','-f',str(sandbox_profile()),str(JAVA),
            '-XX:-UsePerfData','-Djava.net.preferIPv4Stack=true','-Duser.home='+str(RUNTIME/'home'),'-Djava.io.tmpdir='+str(RUNTIME/'tmp'),*map(str,args)]


def sandbox_test():
    if not (BUILD/'lab-agent.jar').exists():build()
    fd,canary=tempfile.mkstemp(prefix='pvp-lab-canary-')
    os.close(fd);Path(canary).write_text('sandbox canary; no private user data')
    try:
        result=subprocess.run(sandbox_java(['-cp',BUILD/'classes','lab.SandboxProbe',canary,RUNTIME/'tmp/probe.txt']),capture_output=True,text=True,timeout=20,env=clean_environment())
        print(result.stdout,end='');print(result.stderr,end='')
        if result.returncode:raise RuntimeError(f'Sandbox verification failed (exit {result.returncode}); no game code will be run')
        (RUNTIME/'sandbox-verification.json').write_text(json.dumps({'time':time.time(),'passed':True,'output':result.stdout}))
    finally:
        Path(canary).unlink(missing_ok=True);Path(canary+'.write').unlink(missing_ok=True)


def logging_test():
    args=['-Dlab.root='+str(ROOT),'-Dlab.role=server','-javaagent:'+str(BUILD/'lab-agent.jar'),
          '-cp',str(BUILD/'classes')+os.pathsep+str(CACHE/'server.jar'),'lab.LoggingProbe']
    result=subprocess.run(sandbox_java(args),capture_output=True,text=True,timeout=20,env=clean_environment())
    if result.returncode:raise RuntimeError('Legacy logging probe failed: '+result.stdout+result.stderr)
    print(result.stdout,end='')
    (RUNTIME/'logging-verification.json').write_text(json.dumps({'passed':True,'time':time.time(),'output':result.stdout}))


def properties(path):
    try:return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))
    except FileNotFoundError:return {}


def match_events(path,identifier):
    """Stream one match without JSON-decoding unrelated historical megabytes."""
    needle=json.dumps(identifier)
    with path.open() as stream:
        for line in stream:
            if needle not in line:continue
            event=json.loads(line)
            if event.get('match')==identifier:yield event


async def latency_proxy(port,configuration,upstream_port=25566):
    async def connection(reader,writer):
        try:up_reader,up_writer=await asyncio.open_connection('127.0.0.1',upstream_port)
        except OSError:writer.close();return
        async def pipe(source,dest):
            queue=asyncio.Queue(maxsize=256)
            async def ingest():
                last_due=0
                while data:=await source.read(65536):
                    delay=float(properties(configuration).get('rtt_ms','0'))/2000
                    due=max(last_due,asyncio.get_running_loop().time()+delay);last_due=due
                    await queue.put((due,data))
                await queue.put((0,None))
            async def deliver():
                while True:
                    due,data=await queue.get()
                    if data is None:return
                    await asyncio.sleep(max(0,due-asyncio.get_running_loop().time()))
                    dest.write(data);await dest.drain()
            try:await asyncio.gather(ingest(),deliver())
            finally:dest.close()
        tasks=[asyncio.create_task(pipe(reader,up_writer)),asyncio.create_task(pipe(up_reader,writer))]
        try:
            await asyncio.wait(tasks,return_when=asyncio.FIRST_COMPLETED)
        finally:
            for task in tasks:task.cancel()
            await asyncio.gather(*tasks,return_exceptions=True)
            writer.close();up_writer.close()
    return await asyncio.start_server(connection,'127.0.0.1',port)


def configure():
    for name in ['server','logs','results','replays','proxy','manager/inbox','tmp']:(RUNTIME/name).mkdir(parents=True,exist_ok=True)
    config='''server-ip=127.0.0.1
server-port=25566
online-mode=false
enable-rcon=false
enable-query=false
broadcast-console-to-ops=false
broadcast-rcon-to-ops=false
level-name=world
level-type=FLAT
generator-settings=3;minecraft:bedrock,2*minecraft:dirt,minecraft:grass;1;
max-players=16
view-distance=8
spawn-protection=0
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
generate-structures=false
gamemode=0
difficulty=2
pvp=true
allow-flight=true
motd=Local PvP Lab - /bots /matches /watch
network-compression-threshold=-1
max-tick-time=60000
'''
    (RUNTIME/'server/server.properties').write_text(config)
    (RUNTIME/'server/eula.txt').write_text('eula=true\n')
    if not (RUNTIME/'proxy/human.properties').exists():(RUNTIME/'proxy/human.properties').write_text('rtt_ms=0\n')
    (RUNTIME/'proxy/observer.properties').write_text('rtt_ms=0\n')
    for i in range(BOT_SLOTS):
        bot=RUNTIME/f'bots/{i}';bot.mkdir(parents=True,exist_ok=True)
        (bot/'control.properties').write_text('enabled=false\n')
        (bot/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nenableVsync:false\nmaxFps:120\nsoundCategory_master:0.0\nshowSubtitles:false\n')
        (RUNTIME/f'proxy/slot{i}.properties').write_text('rtt_ms=0\n')
    assets=CACHE/'assets';assets.mkdir(exist_ok=True)


async def serve(frame_sleep_ms=10):
    if not isinstance(frame_sleep_ms,int) or not 1<=frame_sleep_ms<=50:
        raise ValueError('Headless frame sleep must be 1 through 50 ms')
    lock_file=RUNTIME/'manager.lock';RUNTIME.mkdir(exist_ok=True)
    lock=open(lock_file,'w')
    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError:raise RuntimeError('PvP Lab is already running')
    verify_cache();sandbox_test();logging_test();configure()
    (RUNTIME/'status.json').unlink(missing_ok=True)
    processes=[];logs=[];proxies=[]
    stop=asyncio.Event()
    loop=asyncio.get_running_loop()
    for sig in (signal.SIGINT,signal.SIGTERM):loop.add_signal_handler(sig,stop.set)
    def launch(role,args,cwd,replace=None):
        log=open(RUNTIME/f'logs/{role}.log','w');logs.append(log)
        environment=clean_environment()
        process=subprocess.Popen(sandbox_java(args),cwd=cwd,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,env=environment)
        if replace is None:processes.append(process)
        else:processes[replace]=process
        return process
    base=['-Xms128M','-Xmx1024M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-javaagent:'+str(BUILD/'lab-agent.jar')]
    server=launch('server',[*base,'-Dlab.role=server','-Dlog4j.configurationFile='+str(CACHE/'server-logging.xml'),'-jar',CACHE/'server.jar','nogui'],RUNTIME/'server')
    try:
        print('Starting the sandboxed real Minecraft server...',flush=True)
        deadline=time.time()+150
        while time.time()<deadline:
            if server.poll() is not None:raise RuntimeError('Server exited; see runtime/logs/server.log')
            status=RUNTIME/'status.json'
            if status.exists() and status.stat().st_mtime>time.time()-3:break
            await asyncio.sleep(.5)
        else:raise RuntimeError('Server did not become ready; see runtime/logs/server.log')
        proxies.append(await latency_proxy(25565,RUNTIME/'proxy/human.properties'))
        proxies.append(await latency_proxy(25569,RUNTIME/'proxy/observer.properties'))
        libraries=json.loads((CACHE/'classpath.json').read_text())
        classpath=os.pathsep.join([str(CACHE/'client.jar'),*libraries])
        def start_bot(i,replace=None):
            name=f'LabSlot{i}';bot=RUNTIME/f'bots/{i}'
            digest=hashlib.sha256((BUILD/'lab-agent.jar').read_bytes()).hexdigest()
            launch(f'bot{i}',[*base,'-Dlab.agent_sha256='+digest,'-Dlab.role=client',f'-Dlab.slot={i}',f'-Dlab.port={25570+i}',
                f'-Dhmc.lwjgl.update_sleep={frame_sleep_ms}',
                '-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),'-Djoml.nounsafe=true',
                '-cp',classpath,'net.minecraft.client.main.Main','--version','1.8.9','--gameDir',bot,
                '--assetsDir',CACHE/'assets','--assetIndex','1.8','--username',name,'--uuid',uuid.uuid3(uuid.NAMESPACE_DNS,name).hex,
                '--accessToken','0','--userProperties','{}','--userType','legacy','--width','320','--height','240'],bot,replace)
        for i in range(BOT_SLOTS):
            proxies.append(await latency_proxy(25570+i,RUNTIME/f'proxy/slot{i}.properties'))
            start_bot(i)
        (RUNTIME/'manager.json').write_text(json.dumps({'pid':os.getpid(),'address':'127.0.0.1:25565','version':'1.8.9','started':time.time(),'frame_sleep_ms':frame_sleep_ms}))
        print(f'Server address: 127.0.0.1:25565 (Minecraft Java 1.8.9). Waiting for {BOT_SLOTS} real clients.',flush=True)
        initialized=False
        while not stop.is_set():
            if server.poll() is not None:raise RuntimeError('Server exited')
            for i,p in enumerate(processes[1:]):
                if p.poll() is not None:raise RuntimeError(f'Bot {i} exited; see runtime/logs/bot{i}.log')
            if not initialized:
                status=json.loads((RUNTIME/'status.json').read_text())
                if len([p for p in status['players'] if p.startswith('LabSlot')])==BOT_SLOTS:
                    # 1.8.9 has no doWeatherCycle rule; renew its maximum
                    # vanilla clear-weather duration on every startup.
                    server.stdin.write('gamerule naturalRegeneration true\ngamerule keepInventory true\nweather clear 1000000\n');server.stdin.flush()
                    initialized=True;print('All clients connected. /bots, /duel <bot>, /mode diamond, /blocksound, /matches, /watch.',flush=True)
            for request in sorted((RUNTIME/'manager/inbox').glob('*.json')):
                payload=json.loads(request.read_text());request.unlink()
                command=payload['command']
                if command=='shutdown':stop.set();break
                if command=='reload-bots':
                    state=json.loads((RUNTIME/'status.json').read_text());busy={m[k] for m in state['matches'] for k in ('a','b')}
                    reloaded=[]
                    for i in range(BOT_SLOTS):
                        if f'LabSlot{i}' in busy:continue
                        (RUNTIME/f'bots/{i}/control.properties').write_text('enabled=false\n')
                        old=processes[i+1];old.terminate()
                        try:old.wait(timeout=3)
                        except subprocess.TimeoutExpired:old.kill();old.wait()
                        await asyncio.sleep(.4);start_bot(i,i+1);reloaded.append(i)
                    print('Reloaded idle bot clients '+str(reloaded)+'; active duels keep running.',flush=True)
                    continue
                if not allowed_console_command(command):continue
                server.stdin.write(command+'\n');server.stdin.flush()
            await asyncio.sleep(.1)
    finally:
        for proxy in proxies:proxy.close()
        try:server.stdin.write('stop\n');server.stdin.flush()
        except (OSError,ValueError):pass
        for process in processes[1:]:process.terminate()
        for process in processes:
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for proxy in proxies:await proxy.wait_closed()
        for log in logs:log.close()
        (RUNTIME/'manager.json').unlink(missing_ok=True)


def send(command):
    try:
        manager=json.loads((RUNTIME/'manager.json').read_text());os.kill(manager['pid'],0)
        state=json.loads((RUNTIME/'status.json').read_text())
        if time.time()-state['time']/1000>5:raise RuntimeError('Server status is stale')
    except (FileNotFoundError,ProcessLookupError,PermissionError,json.JSONDecodeError) as error:
        raise RuntimeError('PvP Lab is stopped. Double-click Start PvP Lab.command first.') from error
    inbox=RUNTIME/'manager/inbox';inbox.mkdir(parents=True,exist_ok=True)
    path=inbox/(str(uuid.uuid4())+'.json');path.write_text(json.dumps({'command':command}))


def verify_ux():
    data=json.loads((RUNTIME/'status.json').read_text())
    if not data['matches']:raise RuntimeError('Start a bot match before verifying spectator controls')
    identifier=max(data['matches'],key=lambda m:m['start'])['id']
    script=f'1500:/bots|2500:/matches|3500:/watch {identifier} a|6500:/watch {identifier} b|9000:/duel trade-wtap 100 20|12000:/ping me 120|14000:/ping bot 40|16000:/ping|18000:/stop|20000:/rematch|23000:/ping|26000:/stop'
    bot=RUNTIME/'bots/observer';bot.mkdir(exist_ok=True);(bot/'control.properties').write_text('enabled=false\n')
    (bot/'options.txt').write_text('renderDistance:6\nfancyGraphics:false\nparticles:2\nsoundCategory_master:0.0\n')
    cp=os.pathsep.join([str(CACHE/'client.jar'),*json.loads((CACHE/'classpath.json').read_text())])
    args=['-Xmx768M','-XX:ActiveProcessorCount=2','-Dlab.root='+str(ROOT),'-Dlab.role=client','-Dlab.slot=observer','-Dlab.port=25569',
          '-Dlab.test_script='+script,'-Dlab.test_duration=29000','-Dlog4j.configurationFile='+str(CACHE/'client-logging.xml'),
          '-javaagent:'+str(BUILD/'lab-agent.jar'),'-cp',cp,'net.minecraft.client.main.Main','--version','1.8.9','--gameDir',str(bot),
          '--assetsDir',str(CACHE/'assets'),'--assetIndex','1.8','--username','LabObserver','--uuid',uuid.uuid4().hex,'--accessToken','0','--userProperties','{}','--userType','legacy']
    launched=time.time()*1000
    with open(RUNTIME/'logs/observer.log','w')as log:
        result=subprocess.run(sandbox_java(args),cwd=bot,stdout=log,stderr=subprocess.STDOUT,timeout=60,
            env=clean_environment())
    text=re.sub(r'§[0-9a-fk-or]','',(RUNTIME/'logs/observer.log').read_text(),flags=re.I)
    results=[json.loads(p.read_text()) for p in (RUNTIME/'results').glob('*.json')]
    ours=[r for r in results if r['a']=='LabObserver' and r['start']>=launched]
    native_views=True;native_attacks=[]
    for match in ours:
        path=RUNTIME/'bots'/match['b'].removeprefix('LabSlot')/'events.jsonl'
        events=[e for line in path.read_text().splitlines() if (e:=json.loads(line)).get('match')==match['id']]
        observed=[e for e in events if e['type']=='observation']
        native_views &= len(observed)>=3 and max((e['target_ticks'] for e in observed),default=0)-min((e['target_ticks'] for e in observed),default=0)>=3
        native_attacks.extend(e for e in events if e['type']=='engine_attack')
    legal_attacks=bool(native_attacks) and all(e.get('target_ready') and e.get('ray_selected_target') and e.get('target_loaded_identity')
        and e.get('native_raycast_distance',999)<=3.00001 for e in native_attacks)
    checks={'roster':'Choose a bot' in text,'spectate':'Spectating '+identifier in text,'duel':'human vs trade-wtap' in text,'stop':'Finished ' in text,
            'live_ping_changes':any(r.get('ping_changes')==2 and r['ping_a']==120 and r['ping_b']==40 for r in ours),
            'rematch_retains_pings':any(r.get('initial_ping_a')==120 and r.get('initial_ping_b')==40 for r in ours),
            'native_target_updates_in_every_duel':bool(ours) and native_views,
            'native_attack_reach_and_live_target':legal_attacks,
            'no_lab_exception':'java.lang.IllegalArgumentException' not in text and 'at lab.' not in text}
    (RUNTIME/'ux-verification.json').write_text(json.dumps({'checks':checks,'exit':result.returncode,'match_watched':identifier},indent=2))
    print(json.dumps(checks,indent=2))
    if result.returncode or not all(checks.values()):raise RuntimeError('UX verification failed; inspect runtime/logs/observer.log')


def run(command,extra,frame_sleep_ms=10):
    if command=='sandbox-test':sandbox_test()
    elif command=='logging-test':logging_test()
    elif command=='serve':asyncio.run(serve(frame_sleep_ms))
    elif command=='stop':send('shutdown')
    elif command=='reload-bots':send('reload-bots')
    elif command=='status':print((RUNTIME/'status.json').read_text())
    elif command=='console':
        text=' '.join(extra)
        if not allowed_console_command(text):raise SystemExit('Supported: console op <name>, console deop <name>, console weather clear 1000000, or lab match commands')
        send(text);print('Queued console command: '+text)
    elif command=='verify-ux':verify_ux()
    elif command=='match':
        if len(extra)<2:raise SystemExit('Usage: python3 lab.py match <bot-a> <bot-b> [ping-a] [ping-b]')
        send('lab match '+' '.join(extra));print('Queued match: '+' '.join(extra))
    elif command=='ping-match':
        if len(extra)!=3:raise SystemExit('Usage: python3 lab.py ping-match <match> <RTT-a> <RTT-b>')
        send('lab ping '+' '.join(extra));print('Queued ping change: '+' '.join(extra))
