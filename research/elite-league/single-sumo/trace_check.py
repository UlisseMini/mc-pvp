"""Replay local native observations on the actual Java 8 bridge and Java 17 roller."""
import gzip
from learner import *
import sys
sys.path.insert(0,str(ROOT))
from forge_runtime import JAVA8

def run():
    paths=[]
    for mid in ('m8575','m8574','m8573','m8572','m8571','m8570','m8569','m8568'):
        paths.extend(sorted((ROOT/'runtime/pov'/mid).glob('*-client-*-events.jsonl.gz')))
    groups=[]; hashes={}
    for path in paths:
        heads={}; rows=[];opponent='NaN'
        with gzip.open(path,'rt') as f:
            for line in f:
                row=json.loads(line);kind=row['type']
                if kind=='duel_hud_ping':opponent=row['opponent'] if row['opponent']>=0 else 'NaN'
                if kind=='duel_local_timing':heads[row['tick']]=(row['head_ns'],row['last_press_ns'])
                if kind!='duel_network' or row['tick'] not in heads:continue
                t,p=heads.pop(row['tick']);v=row['observation'].copy()
                v[9:11]=map(int,v[9:11]);v[11:13]=[x if x>=0 else 'Infinity' for x in v[11:13]]
                rows.append('\t'.join(map(str,[*v,opponent,t/1e6,(t-p)/1e6 if p else 'Infinity',row['action']])))
        if rows:
            groups.append('#'+str(773+len(groups))+'\n'+'\n'.join(rows))
            hashes[str(path.relative_to(ROOT))]=hashlib.sha256(path.read_bytes()).hexdigest()
    assert len(groups)>=3
    trace=BUILD/'native-trace.tsv';trace.write_text('\n'.join(groups)+'\n')
    classes=BUILD/'trace-classes';classes.mkdir(exist_ok=True)
    subprocess.run(['javac','--release','8','-cp',BUILD/'bridge.jar','-d',classes,HERE/'LeagueTrace.java'],check=True)
    result={}
    for kind in ('gru','mlp'):
        outs=[]
        for j,cp in ((JAVA8,[classes,BUILD/'bridge.jar']),('java',[classes,BUILD/'classes',ROOT/'build/elite-league/sumo-confidence/classes'])):
            outs.append(subprocess.check_output([j,'-XX:+UseSerialGC','-cp',os.pathsep.join(map(str,cp)),
                         'LeagueTrace',BUILD/(kind+'.net'),trace],text=True).strip())
        if outs[0]!=outs[1]:raise AssertionError((kind,outs))
        result[kind]=outs[0]
    write_json(EVIDENCE/'runtime-parity.json',dict(pov=hashes,fixture='random initialization; replay of recorded applied keys',results=result),fixed=True)
    print(result,flush=True)
if __name__=='__main__':run()
