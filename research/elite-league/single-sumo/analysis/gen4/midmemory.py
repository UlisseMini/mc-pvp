"""Single memory interventions after 24, 100 and 240 decisions, matched recipients and sham replay."""
from concurrent.futures import ThreadPoolExecutor
import gzip,json,subprocess
from review import G4,G3,WT,SEVEN,COUNTER,FOES,CP,WORK,fixtures

def run(at):
    def sim(foe,label,rows):
        pre=WORK/f'memory-{at}-{foe}-{label}';plan=pre.with_suffix('.tsv');out=pre.with_suffix('.jsonl')
        plan.write_text(''.join('\t'.join(map(str,r))+'\n' for r in rows))
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx600m',
                        '-Dreview.swapAt='+str(at+1),'-cp',CP,'Gen4Review',str(plan),str(out)],check=True)
        return [json.loads(l) for l in out.read_text().splitlines()]
    catalogs={}
    for foe in FOES:
        cases=fixtures(G4,foe)
        rs=sim(foe,'donors',[[*r,'catalog','-'] for r,*_ in cases])
        catalogs[foe]={}
        for (_,k,w,t),v in zip(cases,rs,strict=True):
            assert (w,t)==(v['outcome'],v['end'])
            catalogs[foe][k]=v
    # Keep Seven and the two strong GRUs as donor styles for long games.
    ref={SEVEN:G3,G3:SEVEN,COUNTER:SEVEN,WT:SEVEN}
    output=[]
    for foe in FOES:
        rows=[]
        for r,k,w,t in fixtures(G4,foe):
            c,rep,s,p,q=k;j=(c,(rep+1)%4,s,p,q)
            donors=[catalogs[foe][k],catalogs[foe][j],catalogs[ref[foe]][j]]
            if not donors[0]['cut'] or any(v['donor'] is None for v in donors):continue
            for arm,v in zip(['swap-sham','swap-same','swap-other'],donors):
                rows.append(([*r,arm,'-',','.join(map(str,v['donor']))],arm,k,w,t))
        rs=sim(foe,'edits',[q[0] for q in rows])
        for (_,arm,k,w,t),v in zip(rows,rs,strict=True):
            if arm=='swap-sham' and (w,t)!=(v['outcome'],v['end']):raise ValueError('Bad continuation')
            output.append(dict(foe=foe,donor=ref[foe],arm=arm,k=k,**v))
    with gzip.open(WORK/f'memory-{at}.gz','wt') as f:
        for v in output:f.write(json.dumps(v)+'\n')
    print(at,len(output),flush=True)
if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(run,[24,100,240]))
