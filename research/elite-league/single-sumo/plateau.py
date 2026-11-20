"""A failed ordinary search alone cannot establish a fixed point."""
from generation import initialize
from train import configure
from run_training import train
from league_tournament import *
from assay import CP
import sys

def search(g,clocks):
    folder=EVIDENCE/f'g{g:03d}'/'plateau';cfg=initialize(g);field=cfg['field'].copy()
    inc=cfg['incumbent'];source=EVIDENCE/f'g{g:03d}'/'main/trainer.pt'
    # Independently frozen searches; original known weakness is a positive control for learning.
    jobs=[('ppo',inc,source),('positive',INCUMBENT,EVIDENCE/'bootstrap-wtap/trainer.pt')]
    for i,(tag,opp,warm) in enumerate(jobs):
        d=folder/tag;configure(d,'gru','exploiter',300017+g*409+i,823441+g*1009+i,field[opp]['reference'],
                           field[BASE_COUNTER]['reference'],FAMILIES,warm=warm)
        train(d,512);field[tag]=pin_model(d)
    es=folder/'evolution';es.mkdir(exist_ok=True,parents=True)
    subprocess.run([sys.executable,HERE.parent/'cpu_limit.py','nice','-n','8','java','-XX:ActiveProcessorCount=2',
                     '-XX:+UseSerialGC','-Xmx1000m','-cp',CP,'LeagueEvolution',es,field[inc]['reference'],str(788147+g)],check=True)
    field['evolution']=dict(reference=str((es/'final.net').relative_to(ROOT)),sha256=hashlib.sha256((es/'final.net').read_bytes()).hexdigest())
    w=Worker();report={}
    try:
        for tag,opp in [('ppo',inc),('evolution',inc),('positive',INCUMBENT),('wtap',inc),('original-wtap',INCUMBENT)]:
            actor=field['wtap' if tag=='original-wtap' else tag]['reference'];rows=[];ids=[]
            for i,tape in enumerate(clocks):
                for seat in 'ab':
                    for a,b in RTTS:
                        rng=random.Random(943793+g*9037+i*65011+a*127+b*31+(seat=='a'))
                        rows.append([actor,field[opp]['reference'],tape,seat,a,b,rng.getrandbits(60),rng.getrandbits(60)]);ids.append([i,seat,a,b])
            res=[]
            for j in range(0,len(rows),32):res.extend(w.run(rows[j:j+32],False))
            values=np.array([r[0] for r in res]).reshape(len(clocks),8).mean(axis=1)
            report[tag]=dict(bounds=interval(values),score=float(values.mean()),games=[k+[r[0]] for k,r in zip(ids,res)])
        report['budgeted_plateau']=bool(report['ppo']['bounds'][1]<.55 and report['evolution']['bounds'][1]<.55 and report['positive']['bounds'][0]>.55)
        write_json(folder/'verdict.json',report,fixed=True);return report
    finally:w.close()
