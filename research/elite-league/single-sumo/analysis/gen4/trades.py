from concurrent.futures import ThreadPoolExecutor
import gzip,json,subprocess
from review import G4,FOES,WORK,CP,fixtures

def evaluate(foe):
    cases=fixtures(G4,foe)
    result=[]
    for arm in ('intact','click-after','forward-after','no-hurt-jump'):
        plan=WORK/('trade-'+foe+'-'+arm+'.tsv')
        plan.write_text(''.join('\t'.join(map(str,[*r,arm,'detail' if k[1]==0 and k[2]=='a' and k[3:]==(31,31) else '-']))+'\n' for r,k,w,t in cases))
        out=plan.with_suffix('.jsonl')
        subprocess.run(['java','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-Xmx640m','-cp',CP,'TradeReview',str(plan),str(out)],check=True)
        data=[json.loads(l) for l in out.read_text().splitlines()]
        for (_,k,w,t),d in zip(cases,data,strict=True):
            if arm=='intact' and (d['outcome'],d['end'])!=(w,t):raise ValueError((foe,k))
            result.append(dict(arm=arm,k=k,**d))
    with gzip.open(WORK/('trade-'+foe+'.gz'),'wt') as f:
        for d in result:f.write(json.dumps(d,separators=(',',':'))+'\n')
if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(evaluate,FOES))
