"""Matched known-ID versus wrong-ID, blind, width and dedicated-MLP comparisons."""
from collections import defaultdict
import sys
from dedicated import *
from league_tournament import interval
from assay import lower_contrast

def clock_protocol():
    folder=DIR/'prospective';path=folder/'assay.json'
    if path.exists():return json.loads(path.read_text())
    latest=max(int(p.stem[1:]) for p in (ROOT/'runtime/results').glob('m[0-9]*.json') if p.stem[1:].isdigit())
    spec=dict(first_future_id=latest+6,calendar_count=32,rule='first complete bot-only sumo timing donors, independent of the GRU generation-one sealed panel')
    write_json(path,spec,fixed=True);return spec

def make_panel(clocks,rep_count):
    cells=[]
    for i,clock in enumerate(clocks):
        for rep in range(rep_count):
            for side in 'ab':
                for a,b in RTTS:
                    idx=len(cells);rng=random.Random(5533711+idx*71023)
                    cells.append((i,rep,side,a,b,clock,rng.getrandbits(60),rng.getrandbits(60)))
    return cells

def arm_paths(update,target,foe):
    ix=FIELD.index(foe);suffix=f'u{update:03d}'
    rows={}
    for role in ('blind','informed','wide','routed'):
        p=DIR/role/suffix
        if not (p/'schema.json').exists():raise ValueError(f'Missing pinned {role} checkpoint at {update}')
        if role=='blind':rows['blind-trained']=lambda k,p=p:p/'unknown.net';continue
        rows[role+'-correct']=lambda k,p=p:p/(foe+'.net')
        rows[role+'-wrong']=lambda k,p=p:p/(FIELD[(ix+1+k%(N-1))%N]+'.net')
        if role in ('informed','wide'):rows[role+'-masked']=lambda k,p=p:p/'unknown.net'
    rows['wtap']=lambda k:'wtap'
    rows['shared-initializer']=lambda k:DIR/'shared-mlp/initial.net'
    p=DIR/('dedicated-'+target)
    early,count,_=budgets(target)
    matched=early if update==64 else count
    if not (p/f'n{matched:05d}.net').is_file():raise RuntimeError('Missing exposure-matched specialist')
    for label,n in [('dedicated-equal-exposure',matched),('dedicated-full-budget',16384)]:
        f=p/f'n{n:05d}.net'
        if f.exists() and (label!='dedicated-full-budget' or update==256):rows[label]=lambda k,f=f:f
    return rows

def evaluate(update,split,clocks,target=INCUMBENT):
    cells=make_panel(clocks,4 if split=='development' else 1)
    root=DIR/f'eval-u{update:03d}-{split}'
    field=list(dict.fromkeys([target,'wtap',BASE_COUNTER,*FIELD]))
    if target!=INCUMBENT:
        root=DIR/'capacity-secondary'/root.name
        field=[target]
    worker=oracle_worker()
    try:
        for name in field:
            p=root/name;arms=arm_paths(update,target,name)
            for arm,lookup in arms.items():
                dest=p/(arm+'.json')
                if dest.exists():continue
                rows=[[lookup(k),name,clock,side,own,other,as_,bs] for k,(_,_,side,own,other,clock,as_,bs) in enumerate(cells)]
                results=[]
                for j in range(0,len(rows),32):results.extend(worker.run(rows[j:j+32],False))
                write_json(dest,[[i,rep,s,a,b,r[0],r[1]] for (i,rep,s,a,b,_,_,_),r in zip(cells,results)],fixed=True)
    finally:worker.close()
    tables={};contrasts=defaultdict(dict);blocks={}
    for name in field:
        tables[name]={}
        for p in sorted((root/name).glob('*.json')):
            raw=json.loads(p.read_text());values=[r[5] for r in raw]
            by=[float(np.mean([r[5] for r in raw if r[0]==i])) for i in range(len(clocks))]
            blocks[(name,p.stem)]=np.array(by)
            counts=lambda v:dict(wins=v.count(1.),draws=v.count(.5),losses=v.count(0.),score=float(np.mean(v)))
            tables[name][p.stem]=dict(**counts(values),seat={s:counts([r[5] for r in raw if r[2]==s]) for s in 'ab'},
                                      rtt={f'{a}/{b}':counts([r[5] for r in raw if r[3:5]==[a,b]]) for a,b in RTTS})
    pairs=[('informed-correct','informed-wrong'),('informed-correct','blind-trained'),('wide-correct','informed-correct'),
           ('routed-correct','informed-correct'),('informed-correct','informed-masked'),('routed-correct','routed-wrong'),
           ('dedicated-equal-exposure','informed-correct'),('dedicated-full-budget','informed-correct'),
           ('dedicated-equal-exposure','routed-correct'),('dedicated-full-budget','dedicated-equal-exposure')]
    for a,b in pairs:
        tag=a+' minus '+b;available=[n for n in field if (n,a) in blocks and (n,b) in blocks]
        if not available:continue
        for n in list(dict.fromkeys(['field',*([target] if target in available else [])])):
            fs=available if n=='field' else [n]
            if n=='field' and len(available)!=len(FIELD):continue
            dif=np.mean([blocks[f,a]-blocks[f,b] for f in fs],axis=0)
            contrasts[tag][n]=dict(estimate=float(dif.mean()),bounds=lower_contrast(dif,.05/(2*len(pairs))))
    # A late-arriving dedicated checkpoint adds a separately named summary; prior evidence is immutable.
    full='dedicated-full-budget' in tables[target]
    write_json(root/('summary-full.json' if full else 'summary.json'),dict(target=target,split=split,cells=tables,
               paired_contrasts=contrasts,calendar_count=len(clocks),weighting='equal per opponent in field, clustered by original calendar'),fixed=True)
if __name__=='__main__':
    update=int(sys.argv[1]);clock_protocol()
    target=sys.argv[3] if len(sys.argv)>3 else INCUMBENT
    if len(sys.argv)>2 and sys.argv[2]=='prospective':
        manifest=DIR/'prospective/calendars/manifest.json'
        if not manifest.exists():raise RuntimeError('Independent calendar capture is pending')
        evaluate(update,'prospective',json.loads(manifest.read_text())['selected'],target)
    else:evaluate(update,'development',CLOCKS['dev'],target)
