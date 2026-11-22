"""Run two isolated Forge 1.8.9 clients and preserve their packets plus server tick telemetry."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
import forge_runtime as forge


def run(out):
    out.mkdir(parents=True)
    for d in ('home', 'tmp'):
        (out/d).mkdir()
    build = ROOT / 'build/practice-client'
    spec = importlib.util.spec_from_file_location('practice_build', ROOT / 'practice-client/build.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    cp = forge.stage_dependencies()
    classes = out / 'probe-classes'
    builder.compile_to(classes, [Path(__file__).with_name('NativeParity.java')],
                       os.pathsep.join(map(str, [build/'compile-api.jar', *cp])))
    fixture = out / 'bow-parity-probe.jar'
    builder.jar(fixture, classes)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    files = {'probe': fixture, 'bridge': forge.BRIDGE, 'module': forge.MODULE, 'client': ROOT/'.cache/client.jar'}
    manifest = dict(minecraft='1.8.9', forge='11.15.1.2318', port=port,
                    sha256={k: forge.digest(v) for k, v in files.items()})
    (out/'manifest.json').write_text(json.dumps(manifest, indent=2))
    processes = []
    try:
        for name, host in [('ParityHost', True), ('ParityA', False), ('ParityB', False)]:
            if name == 'ParityA':
                for _ in range(160):
                    if (out/'ready').is_file():
                        break
                    if processes[0].poll() is not None:
                        raise RuntimeError('Host exited before opening the local server')
                    time.sleep(.2)
                else:
                    raise RuntimeError('Host did not start its local test server')
            game = out / name
            for d in ('mods', 'config/pvp-practice', 'home', 'tmp'):
                (game/d).mkdir(parents=True, exist_ok=True)
            for src, dst in ((fixture, 'mods/bow-parity-probe.jar'),
                             (forge.BRIDGE, 'mods/practice-bridge.jar'),
                             (forge.MODULE, 'config/pvp-practice/practice-module.jar')):
                shutil.copyfile(src, game/dst)
            (game/'config/pvp-practice/settings.properties').write_text(
                (ROOT/'practice-client/settings.properties').read_text() +
                '\nrecord.enabled=false\ncapture.enabled=true\nsprint.enabled=false\n')
            (game/'config/splash.properties').write_text('enabled=false\n')
            (game/'options.txt').write_text('renderDistance:3\nmaxFps:30\nsoundCategory_master:0.0\n')
            options = ['-Xmx768M', '-Dhmc.lwjgl.update_sleep=1', f'-Dbowparity.host={str(host).lower()}',
                       f'-Dbowparity.first={str(name == "ParityA").lower()}',
                       f'-Dbowparity.dir={out}', f'-Dbowparity.port={port}',
                       f'-Dlog4j.configurationFile={ROOT}/.cache/server-logging.xml',
                       f'-javaagent:{forge.HEADLESS}', '-cp', os.pathsep.join(map(str, cp)),
                       'net.minecraft.launchwrapper.Launch', '--username', name, '--version', '1.8.9',
                       '--gameDir', str(game), '--assetsDir', str(forge.ASSETS), '--assetIndex', '1.8',
                       '--uuid', uuid.uuid3(uuid.NAMESPACE_DNS, name).hex, '--accessToken', '0',
                       '--userProperties', '{}', '--userType', 'legacy',
                       '--tweakClass', 'net.minecraftforge.fml.common.launcher.FMLTweaker']
            with (out/(name+'.log')).open('wb') as log:
                processes.append(subprocess.Popen(forge.command(options, out), cwd=game, stdout=log,
                                                   stderr=subprocess.STDOUT,
                                                   env={'PATH': '/usr/bin:/bin', 'LANG': 'en_US.UTF-8'}))
        until = time.monotonic() + 120
        for child in processes:
            if child.wait(timeout=max(1, until-time.monotonic())):
                raise RuntimeError(f'Forge process exited {child.returncode}. See the archived logs.')
    finally:
        for child in processes:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=10)
    if not (out/'done').exists():
        raise RuntimeError('Server never completed the scenario schedule')
    captures = []
    for name in ('ParityA', 'ParityB'):
        packets = sorted((out/name/'config/pvp-practice/captures').glob('*.jsonl.gz'))
        if not packets:
            raise RuntimeError(f'Missing packet recording for {name}')
        captures.extend(packets)
    captures += [out / name for name in ('client-a.tsv', 'client-b.tsv', 'server.tsv',
                                        'ParityA.log', 'ParityB.log', 'ParityHost.log')]
    manifest['recordings'] = {str(file.relative_to(out)): forge.digest(file) for file in captures}
    (out/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(out)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    run(parser.parse_args().out.resolve())
