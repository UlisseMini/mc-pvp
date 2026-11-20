"""Test whether opponent-specific history improves play after an identical three-hurt-cue fork."""
import sys
import os
import time
from collections import defaultdict

import numpy as np

from assay import donors, gzdata, inputs, java_rows, lower_contrast
from learner import BUILD, CLOCKS, Worker, write_json
from remote_league import OUT, read


def run(role,prospective=False,head24=False):
    folder=OUT/('history-prospective-head24' if head24 else 'history-prospective' if prospective else 'history')/role
    p=read(OUT/'protocol.json')
    styles={'early':{'reference':'v4-ppo-g0496'},'late':{'reference':'sumo-seven-first'}}
    experts=[p['entrants'][n]['reference'] for n in ('incumbent','seven')]
    clocks=CLOCKS['dev']
    if prospective:
        selection=read(OUT/('response-reversal-head24' if head24 else 'response-reversal')/'result.json')
        if not selection['confirmed']:raise ValueError('Preregistered payoff reversal failed development confirmation')
        styles={s:dict(reference=n) for s,n in zip(('early','late'),selection['opponents'],strict=True)}
        experts=selection['experts']
        clocks=read(OUT/('prospective-head24' if head24 else 'prospective')/'calendars/manifest.json')['selected']
    labels={k:v['reference'] for k,v in styles.items()}
    def simulate(program,rows,*args,**kwargs):
        if head24:
            from assay import CP
            overlay=BUILD/read(BUILD/'head24-diagnostic.json')['directory']
            kwargs.update(java_opts=['-Dleague.commitHeads=24'],classpath=str(overlay)+os.pathsep+CP)
        return java_rows(program,rows,*args,**kwargs)
    write_json(folder/'protocol.json',dict(opponents=labels,experts=experts,
               inference='Both actors receive only their own observations. '+('At input head 24, ' if head24 else 'At the third visible hurt cue, ')+'replay the last ten observations with same-opponent, other-opponent or zero older memory. Own-history sham must reproduce the intact fight.',
               donors='Different timing tape; same seat, both pings and ground states; matched time, last hurt times, range, and both velocities. Up to 256 matched recipients per opponent after at most 32 replications.',
               metric='Outright ring-outs. Cluster paired differences by original timing donor. Require simultaneous positive intact-minus-wrong and same-minus-wrong effects for both opponents, opposite specialist advantages and changed physical inputs. '+('Prospectively reserved transfer calendars.' if prospective else 'Open development calendars.')),fixed=True)
    if role=='parent':actor=p['initial']['gru']['reference']
    else:
        while not (OUT/role/'result.json').exists():time.sleep(8)
        actor=read(OUT/role/'result.json')['reference']
    data=[]
    def wdl(vals):return [sum(v==i for v in vals) for i in (1,.5,0)]
    for rep in range(32):
        keys,rows=inputs(actor,styles,clocks,rep,9073)
        raw=gzdata(folder/f'catalog-{rep:02}.json.gz',lambda:simulate('MemoryAssay',rows,mode='catalog'))
        data.extend(zip(keys,raw,rows,strict=True))
        chosen,coverage=donors(data)
        if min(map(len,chosen.values()))==256:break
    selected=[(k,i,s,o) for k,group in chosen.items() for i,s,o in group]
    batch=[[*data[i][2],data[i][1]['context'][0],','.join(map(str,data[s][1]['donor'])),
            ','.join(map(str,data[o][1]['donor'])),int(j<4)] for j,(_,i,s,o) in enumerate(selected)]
    outcomes=gzdata(folder/'interventions.json.gz',lambda:simulate('MemoryAssay',batch,mode='fork') if batch else [])
    replies=gzdata(folder/'expert-continuations.json.gz',lambda:simulate('ResponseReversal',[data[i][2] for _,i,_,_ in selected],[','.join(['wtap',*experts])]) if batch else [])
    baseline=Worker(current_runtime=True)
    try:
        plain=gzdata(folder/'wtap-same-runtime.json.gz',lambda:[r[0] for j in range(0,len(data),32) for r in baseline.run(
                     [['wtap',*row[1:]] for _,_,row in data[j:j+32]],False)])
    finally:baseline.close()
    legacy=folder/'wtap-from-spawn.json.gz'
    if legacy.is_file():
        import gzip
        import json
        if plain!=[json.loads(x) for x in gzip.decompress(legacy.read_bytes()).splitlines()]:raise ValueError('Training and assay runtimes differ on W-tap')
    write_json(folder/'control-runtime.json',dict(plain_wtap_games=len(plain),runtime='same simulator snapshot as the memory forks'),fixed=True)
    result={};strong=True
    for style in styles:
        indices=[j for j,v in enumerate(selected) if v[0]==style]
        all_i=[i for i,v in enumerate(data) if v[0][0]==style]
        metrics=defaultdict(lambda:defaultdict(list));actions=0
        for j in indices:
            _,i,_,_=selected[j];key,orig,_=data[i];r=outcomes[j];e=replies[j]
            if (orig['outcome'],orig['time'])!=(r['intact']['score'],r['intact']['end']) or e['cut']!=orig['context'][0] or e['intact']!=orig['outcome']:
                raise ValueError('Causal continuation diverged')
            for arm in ('intact','same'):
                metrics[arm+'-wrong'][key[1]].append(int(r[arm]['score']==1)-int(r['opposite']['score']==1))
            a,b=experts if style=='early' else experts[::-1]
            metrics['expected-expert-gain'][key[1]].append(int(e[a]==1)-int(e[b]==1))
            actions+=int(r['same']['actions']!=r['opposite']['actions'])
        estimates={}
        for name,donors_ in metrics.items():
            means=[np.mean(v) for v in donors_.values()]
            estimates[name]=[float(np.mean(means)),*lower_contrast(means,.05/6)] if len(means)>=2 else None
        strong &= len(indices)==256 and actions>0 and all(estimates.get(n) and estimates[n][1]>0 for n in ('same-wrong','intact-wrong','expected-expert-gain'))
        result[labels[style]]=dict(total=len(all_i),matched=len(indices),coverage=coverage[style],changed_action_sequences=actions,
                    from_spawn=wdl([data[i][1]['outcome'] for i in all_i]),plain_wtap=wdl([plain[i] for i in all_i]),
                    fork_wtap=wdl([replies[j]['wtap'] for j in indices]),paired_estimates=estimates)
    grade='qualified_on_prospective_calendars' if prospective else 'qualified_in_development'
    write_json(folder/'result.json',{grade:bool(strong),'opponents':result},fixed=True)


if __name__=='__main__':run(sys.argv[1],bool(sys.argv[2:]),sys.argv[2:]==['prospective-head24'])
