"""Count counterfactual key changes on the exact native states visited by two reference policies."""
import json
import subprocess
import sys
from live import D,B,ROOT,wait,read
sys.path.insert(0,str(ROOT/'research/sumo-rl'));import captures
def main():
    wait(lambda:(D/'screen-audit.json').exists());a=read(D/'screen-audit.json')
    out=['arm,match,disabled,phase,decisions,changed,forward_or_sprint,strafe,click,jump']
    for r in a['rows'].values():
        name=r['arm']
        if name not in ('sumo-input-base8','sumo-input-full32'):continue
        seat=r['seat'];mid=r['mid'];g=read(ROOT/'runtime/results'/(mid+'.json'))
        net=g['parameters_'+seat]['duel_network']
        f,=(ROOT/'runtime/pov'/mid).glob(seat+'-client-*-events.jsonl.gz')
        events=[e for e in captures.events(f,{'duel_network','duel_local_timing'}) if e.get('match')==mid]
        timing={e['tick']:e for e in events if e['type']=='duel_local_timing'}
        query=[net]
        for e in events:
            if e['type']!='duel_network':continue
            t=timing[e['tick']];o=e['observation'];age=(t['head_ns']-t['last_press_ns'])/1e6 if t['last_press_ns'] else -1
            query.append(' '.join(map(str,[*o[:9],int(o[9]),int(o[10]),*o[11:],t['head_ns']/1e6,age,e['action'],int(g['start']<=e['time']<=g['finish'])])))
        txt=subprocess.run(['java','-XX:ActiveProcessorCount=1','-Xmx64m','-cp',B/'classes','InputDecisionTrace'],text=True,input='\n'.join(query)+'\n',capture_output=True,check=True).stdout
        for line in txt.splitlines():out.append(name+','+mid+','+line)
    (D/'action-interventions.csv').write_text('\n'.join(out)+'\n')
if __name__=='__main__':main()
