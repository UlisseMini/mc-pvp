"""Close the already frozen cap curriculum after its optional native confirmation."""
from time import sleep
from cap_curriculum import C,D,PRIOR,OLD,WT,wait
from confidence_analysis import native_blocks,model_rows,summary,breakdown,physical
from confidence_report import table,wld
from study import freeze

def run():
    ready=wait(C/'ready.json')
    if not ready['qualified']:
        freeze(C/'assessment.json',dict(qualified=False,selection=ready))
        return
    while not (C/'model/transfer-20.csv').exists():sleep(8)
    native,invalid=native_blocks('t',16)
    assert len(native)==112
    actor='sumo-cap-'+ready['primary']
    scores={}
    for name,rows in [('native',native),('simulator',model_rows(C/'model/transfer-20.csv'))]:
        scores[name]={}
        for arm in (actor,PRIOR,WT):
            group=[r for r in rows if r['arm']==arm and r['foe']==OLD and (name=='native' or r['own']!=r['other'] or r['own']==80)]
            assert group
            score={'overall':summary(group),'conditions':breakdown(group)}
            if name=='native':score['inputs']=physical(group)
            scores[name][arm]=score
    freeze(C/'assessment.json',dict(qualified=True,eligible_native=len(native),invalid=invalid,selection=ready,scores=scores))
    lines=['# Transferring the 7-CPS response to 20 CPS','',
        'The frozen target and both contestants used the 20-CPS cap. The simulator table includes the two asymmetric links and 80/80 equally. The native contender had four asymmetric fixtures and one equal fixture per block; its references used the rotating asymmetric cell. The per-condition results are in assessment.json.', '',
        *table(['Game engine',actor+' W–L–D',PRIOR+' W–L–D','Plain W-tap W–L–D'],[(k,*[wld(v[n]['overall']) for n in (actor,PRIOR,WT)]) for k,v in scores.items()])]
    (C/'README.md').write_text('\n'.join(lines))
if __name__=='__main__':run()
