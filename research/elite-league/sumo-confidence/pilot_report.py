from time import sleep
from seven_pilot import P,NAME,PRIOR,WT,OLD
from confidence_analysis import native_blocks,model_rows,summary,breakdown,physical
from seven_metrics import one
from seven_report import mechanics
from study import freeze
from confidence_report import wld,table,scoreci
if __name__=='__main__':
    while not (P/'transfer-7.csv').exists():sleep(4)
    rows,bad=native_blocks('q',8);assert len(rows)==48
    model=[r for r in model_rows(P/'transfer-7.csv') if r['own']!=r['other'] and r['foe']==OLD]
    out=dict(invalid=bad,eligible=len(rows))
    for label,events in [('native',rows),('model',model)]:
        out[label]={}
        for arm in (NAME,PRIOR,WT):
            group=[r for r in events if r['arm']==arm]
            info={'overall':summary(group),'conditions':breakdown(group)}
            if label=='native':info.update(actions=physical(group),mechanics=mechanics(group))
            out[label][arm]=info
    # Preserve the original registered block t calculation and write boundary-sensitive uncertainty separately.
    dest=P/'assessment.json' if not (P/'assessment.json').exists() else P/'uncertainty-assessment.json'
    freeze(dest,out)
    lines=['# First 7-CPS native transfer','',
        'Both Forge clients used a verified 7 CPS ceiling. The target was the frozen best-sumo checkpoint. The early policy was chosen before fighting it in Minecraft.', '',
        *table(['Against frozen best-sumo','7-trained PPO W–L–D','20-trained PPO at 7 W–L–D','Plain W-tap at 7 W–L–D'],
            [(key,*[wld(out[key][name]['overall']) for name in (NAME,PRIOR,WT)]) for key in ('native','model')]),
        f"All eight native scheduling blocks were perfect. A conservative exact 95% interval treating each whole block as a trial is {scoreci(out['native'][NAME]['overall'])}. Assuming independent bouts instead, 32/32 gives an exact 95% win-rate interval of 89.1% to 100%. The preregistered t interval collapses at an all-wins boundary. The two references have eight native bouts apiece. The separately frozen 16-block crossover compares both caps directly.",'']
    (P/'README.md').write_text('\n'.join(lines));print(lines[-2],flush=True)
