#!/usr/bin/env python3
"""Exercise brief native guards through all three timing feedback paths."""
import hashlib
import json
import os
import re
import subprocess
import time
import uuid

from lab import ROOT, CACHE, BUILD, RUNTIME
from lab_runtime import sandbox_java, clean_environment


def run():
    state = json.loads((RUNTIME / 'status.json').read_text())
    if 'LabObserver' in state['players']:
        raise RuntimeError('Another native observer test is running')
    directory = RUNTIME / 'bots/observer'
    (directory / 'control.properties').write_text('enabled=false\n')
    out = ROOT / 'experiments' / ('block-feedback-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    # The 275 ms pulse period sweeps across the opponent's roughly 500 ms hit cycle.
    windows = ','.join(f'{t}-{t+100}' for t in range(6500, 19000, 275))
    script = '1500:/duel hold-w diamond_box 0 0|21000:/stop'
    cp = os.pathsep.join([str(CACHE / 'client.jar'), *json.loads((CACHE / 'classpath.json').read_text())])
    args = ['-Xmx768M', '-XX:ActiveProcessorCount=2', '-Dlab.root=' + str(ROOT), '-Dlab.role=client',
            '-Dlab.slot=observer', '-Dlab.port=25569', '-Dhmc.lwjgl.update_sleep=10',
            '-Dlab.test_script=' + script, '-Dlab.test_duration=23000', '-Dlab.practice_block_windows=' + windows,
            '-Dlog4j.configurationFile=' + str(CACHE / 'client-logging.xml'), '-javaagent:' + str(BUILD / 'lab-agent.jar'),
            '-cp', cp, 'net.minecraft.client.main.Main', '--version', '1.8.9', '--gameDir', str(directory),
            '--assetsDir', str(CACHE / 'assets'), '--assetIndex', '1.8', '--username', 'LabObserver',
            '--uuid', uuid.uuid3(uuid.NAMESPACE_DNS, 'LabObserver').hex, '--accessToken', '0', '--userProperties', '{}', '--userType', 'legacy']
    started = time.time() * 1000
    with (out / 'client.log').open('w') as log:
        subprocess.run(sandbox_java(args), cwd=directory, stdout=log, stderr=subprocess.STDOUT,
                       env=clean_environment(), timeout=60, check=True)
    rows = [e for line in (directory / 'practice.jsonl').read_text().splitlines()
            if (e := json.loads(line)).get('time', 0) >= started]
    bars = [{'time': e['time'], 'text': re.sub('§[0-9a-fk-or]', '', e['action_bar'])} for e in rows if e.get('action_bar')]
    checks = {kind: any(kind in e['text'] for e in bars) for kind in ('BLOCKED', 'EARLY', 'LATE')}
    report = {'passed': all(checks.values()), 'checks': checks, 'script': script, 'block_windows': windows,
              'agent_sha256': hashlib.sha256((BUILD / 'lab-agent.jar').read_bytes()).hexdigest()}
    (out / 'report.json').write_text(json.dumps(report, indent=2))
    (out / 'action-bars.json').write_text(json.dumps(bars, indent=2))
    for p in (RUNTIME / 'results').glob('*.json'):
        g = json.loads(p.read_text())
        if g['a'] == 'LabObserver' and g['start'] >= started:
            (out / (g['id'] + '.json')).write_text(json.dumps(g, indent=2))
            (out / (g['id'] + '.jsonl')).write_bytes((RUNTIME / 'replays' / (g['id'] + '.jsonl')).read_bytes())
    print(json.dumps({'directory': str(out), **report}, indent=2), flush=True)
    assert report['passed'], 'Missing native timing feedback path'


if __name__ == '__main__':
    run()
