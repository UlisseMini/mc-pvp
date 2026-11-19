"""Verify all tournament aliases and historical configurations against the running test server."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
from adaptation import properties
from native_trials import active_bridge
from run import HERE,ROOT
from runtime_checks import JAVA8

def run(out):
    declared=json.loads((HERE/'adaptive-field/policy-index.json').read_text())
    aliases={p.stem:properties(p) for p in (ROOT/'policies').glob('*.properties')}
    winners={n:p for n,p in aliases.items() if 'league_stage' in p}
    names=sorted(set(declared)|set(winners)|{'best-sumo','best-boxing'})
    status=json.loads((ROOT/'runtime/status.json').read_text())
    if time.time()-status['time']/1000>5:raise ValueError('Stale main server status')
    missing=set(names)-set(status['policies'])
    if missing:raise ValueError('Names missing from the current server list: '+str(sorted(missing)))
    jar=ROOT/'runtime/bots/0/mods/practice-bridge.jar';build=hashlib.sha256(jar.read_bytes()).hexdigest()
    if active_bridge()!=build:raise ValueError('The eight loaded clients have different bridges')
    with tempfile.TemporaryDirectory() as temp:
        temp=Path(temp);index=temp/'names.txt';index.write_text('\n'.join(names)+'\n')
        subprocess.run(['javac','-J-XX:ActiveProcessorCount=2','--release','8','-proc:none','-sourcepath','','-cp',str(jar),'-d',str(temp),str(HERE/'PlayableCheck.java')],check=True)
        output=subprocess.check_output([str(JAVA8),'-Dlab.root='+str(ROOT),'-Xms12m','-Xmx96m','-XX:+UseSerialGC','-cp',str(temp)+':'+str(jar),'PlayableCheck',str(index)],cwd=ROOT,text=True)
    parsed={l.split(' ',1)[0]:l.split(' ',1)[1] for l in output.splitlines()}
    if set(parsed)!=set(names):raise ValueError('Java configuration check dropped a profile')
    rejected={n:m for n,m in parsed.items() if m!='ok'}
    if any(n in winners or n.startswith('best-') for n in rejected):raise ValueError('A published champion cannot load: '+str(rejected))
    groups={n:dict(track=winners[n]['league_track'],mode=winners[n]['league_mode'],source=winners[n]['league_source_sha256'],
                   settings=hashlib.sha256((ROOT/'policies'/(n+'.properties')).read_bytes()).hexdigest()) for n in sorted(winners)}
    with out.open('x') as f:json.dump(dict(server=status['version'],bridge=build,checked=len(names),historical=len(declared),
                    current_titles={n:aliases[n]['title_source'] for n in ('best-sumo','best-boxing')},winners=groups,rejected=rejected),f,indent=2);f.write('\n')
    print(len(names),'server-listed profiles;',len(groups),'published titles;',len(rejected),'rejected configurations',flush=True)

if __name__=='__main__':
    from cpu_limit import supervise_script
    supervise_script();p=argparse.ArgumentParser(description=__doc__);p.add_argument('out',type=Path);run(p.parse_args().out)
