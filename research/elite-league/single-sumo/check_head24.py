"""Verify the opening fork on the diagnostic overlay and reproduce the original three-cue behavior."""
import os
from assay import CP, java_rows
from learner import BUILD,CLOCKS
from remote_league import OUT,read
from remote_bundle import build_head24_overlay
from learner import write_json


def run():
    build_head24_overlay()
    overlay=BUILD/read(BUILD/'head24-diagnostic.json')['directory']
    cp=str(overlay)+os.pathsep+CP
    actor=read(OUT/'protocol.json')['initial']['gru']['reference']
    rows=[[actor,foe,clock,seat,31,109,380021+i,140611+i] for i,(foe,clock,seat) in enumerate(
            (f,c,s) for f in ('v4-ppo-g0496','sumo-seven-first') for c in CLOCKS['dev'][:2] for s in 'ab')]
    original=java_rows('MemoryAssay',rows,mode='catalog')
    if original!=java_rows('MemoryAssay',rows,mode='catalog',classpath=cp):raise ValueError('Default cue behavior changed')
    opts=['-Dleague.commitHeads=24']
    result=java_rows('MemoryAssay',rows,mode='catalog',classpath=cp,java_opts=opts)
    forks=[]
    for row,r in zip(rows,result,strict=True):
        state=','.join(map(str,r['donor']))
        forks.append([*row,r['context'][0],state,state,1])
    repeats=java_rows('MemoryAssay',forks,mode='fork',classpath=cp,java_opts=opts)
    wt=java_rows('ResponseReversal',rows,args=['wtap'],classpath=cp,java_opts=opts)
    for r,b,w in zip(result,repeats,wt,strict=True):
        if w['cut']!=r['context'][0] or w['intact']!=r['outcome']:raise ValueError('Response and memory landmarks differ')
        for k in ('same','opposite'):
            for measure in ('actions','next_fresh','end','score'):
                if b[k][measure]!=b['intact'][measure]:raise ValueError('Restoring original history changed the fight')
    write_json(OUT/'head24-replay.json',dict(fixtures=len(rows),original_catalogs_identical=True,
            identical_history_branches=2*len(rows),full_clock_hash_sham=len(rows),
            frozen_cuts_ms=[r['context'][0] for r in result],
            matched_wtap=[w['wtap'] for w in wt]),fixed=True)


if __name__=='__main__':run()
