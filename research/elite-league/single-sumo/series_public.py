"""Read only the participant's public countdown and result messages for cross-match learning."""
import base64
import gzip
import json
import re
from decimal import Decimal

from learner import ROOT


def varint(data,at):
    value=0
    for shift in range(0,35,7):
        b=data[at];at+=1;value|=(b&127)<<shift
        if b<128:return value,at
    raise ValueError('Invalid Minecraft message length')


def plain(component):
    if isinstance(component,str):return component
    if isinstance(component,list):return ''.join(map(plain,component))
    if not isinstance(component,dict):return str(component)
    return component.get('text','')+plain(component.get('extra',[]))


def messages(mid,side):
    packets=[]
    for file in (ROOT/'runtime/pov'/mid).glob(side+'-client-*-events*.jsonl.gz'):
        with gzip.open(file,'rb') as stream:
            for line in stream:
                if b'packet_wire' not in line or b'S02PacketChat' not in line:continue
                event=json.loads(line);record=event['data']
                if event['type']!='packet_wire' or record['packet']['kind']!='S02PacketChat' or record['packet']['direction']!='in':continue
                data=base64.b64decode(record['base64'],validate=True)
                kind,i=varint(data,0);length,i=varint(data,i)
                if kind!=2 or i+length+1!=len(data):raise ValueError('Unexpected 1.8 chat frame')
                text=re.sub('§.','',plain(json.loads(data[i:i+length])))
                packets.append((event['time_ms'],data[-1],text))
    return sorted(packets)


def observed(mid,side):
    ping=None;score=None;duration=None
    for ts,position,text in messages(mid,side):
        match=re.search(r'Starting sumo in .*?RTT (\d+)/(\d+) ms',text)
        if position==2 and match and ping is None:
            a,b=map(int,match.groups());ping=(ts,a,b) if side=='a' else (ts,b,a)
        if text.startswith('Finished '+mid+' [sumo]'):
            if 'YOU WON' in text:score=1.
            elif 'YOU LOST' in text:score=0.
            elif 'draw' in text.lower():score=.5
            else:raise ValueError('Unrecognized public match result '+mid)
        if score is not None:
            match=re.fullmatch(r'.+ · (\d+\.\d) seconds',text)
            if match:duration=int(1000*Decimal(match[1]));break
    if ping is None or score is None or duration is None:raise ValueError('Incomplete public countdown or result in own POV for '+mid+' '+side)
    return dict(countdown_received_ms=ping[0],own_ping=ping[1],opponent_ping=ping[2],score=score,displayed_duration_ms=duration)


def clock_test(task,budget):
    import time
    import numpy as np
    import torch
    from learner import write_json
    from series_bank import DEV_REPS,FOLDER,ROUNDS
    from series_eval import load_models,matches,series,tally
    from series_learn import bank
    root=FOLDER if task=='field' else FOLDER/task
    while not (root/'dev'/f'evaluation-{budget:02}.json').is_file():time.sleep(15)
    if task=='archive-reversal':
        from series_archive import load as loader
    elif task=='diverse-experts':
        from series_expand import load as loader
    else:loader=bank
    data,c,wt=loader('dev',DEV_REPS);nc,nr,_,nf,na,_=data.shape
    full=data.permute(0,1,3,2,4,5).reshape(-1,ROUNDS,na,2);rounded=full.clone()
    rounded[...,1]=100*torch.floor((rounded[...,1]+50)/100)
    ctx=c[:,:,None].expand(nc,nr,nf,ROUNDS,3).reshape(-1,ROUNDS,3)
    rivals=np.tile(np.arange(nf),nc*nr);tapes=np.repeat(np.arange(nc),nr*nf)
    stats={}
    for k,h in [('blind',-1),('scoreboard',0),('one-game',1),('three-games',3),('nine-games',9)]:
        nets=load_models(budget,h,na,nf,root)
        a=matches(nets,full,ctx,rivals,tapes);b=matches(nets,rounded,ctx,rivals,tapes)
        stats[k]=dict(different_choices=int((a['choices']!=b['choices']).sum()),different_series=int((series(a['won'])!=series(b['won'])).sum()),
                      original_series=tally(series(a['won'])),public_series=tally(series(b['won'])),public_games=tally(b['won']))
    if task=='field':
        from series_bayes import play
        model=FOLDER/'belief'/f'learn-{budget:02}.npz'
        while not model.exists():time.sleep(10)
        with np.load(model) as saved:edges,probs=saved['edges'],saved['probabilities']
        a=play(edges,probs,full.numpy(),ctx.numpy(),rivals,tapes,9,True)
        b=play(edges,probs,rounded.numpy(),ctx.numpy(),rivals,tapes,9,True)
        stats['belief']=dict(different_choices=int((a['act']!=b['act']).sum()),different_series=int((series(a['won'])!=series(b['won'])).sum()),
            original_series=tally(series(a['won'])),public_series=tally(series(b['won'])),public_games=tally(b['won']))
    controls=wt.permute(0,1,3,2).reshape(-1,ROUNDS).numpy()
    write_json(root/'dev'/f'displayed-clock-{budget:02}.json',dict(choices_per_policy=int(controls.size),policies=stats,
            wtap=dict(series=tally(series(controls)),games=tally(controls))),fixed=True)


if __name__=='__main__':
    import sys
    for budget in ((2,8) if sys.argv[1]=='diverse-experts' else (2,16)):
        clock_test(sys.argv[1],budget)
