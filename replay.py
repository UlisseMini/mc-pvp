#!/usr/bin/env python3
"""Export recorded native trajectories as a portable, offline analysis replay."""
import argparse
import json
from pathlib import Path
from lab import ROOT, RUNTIME


def export(identifier):
    if not identifier.startswith('m') or not identifier[1:].isdigit():
        raise ValueError('Use a match ID such as m0024')
    from science import server_events, client_events
    events=server_events(identifier)
    match=next(e['match'] for e in events if e['type']=='start')
    finish=next((e['result'] for e in events if e['type']=='finish'),None)
    if not finish:raise ValueError('Wait for the match to finish')
    labels=ROOT/'experiments/legacy-policy-labels.json'
    aliases=json.loads(labels.read_text()).get('matches',{}).get(identifier,{}) if labels.exists() else {}
    for side in ('a','b'):
        match['policy_'+side]=aliases.get(match['policy_'+side],match['policy_'+side])
        finish['policy_'+side]=aliases.get(finish['policy_'+side],finish['policy_'+side])
    ticks=[e for e in events if e['type']=='tick' and e['time']>=match['start']]
    if not ticks:raise ValueError('No native trajectory samples')
    clients={}
    for side in ('a','b'):
        # Preserve the lightweight viewer; full scientific diagnostics remain
        # in the independently archived raw traces.
        clients[side]=[e for e in client_events(finish,side) if not e['type'].startswith('science_')]
    strict=True;click_count=0
    for side in ('a','b'):
        clicks=[e for e in clients[side] if e['type']=='click'];click_count+=len(clicks)
        strict &= all('monotonic_ns' in e for e in clicks)
        if strict:strict &= all(b['monotonic_ns']-a['monotonic_ns']>=100_000_000 for a,b in zip(clicks,clicks[1:]))
    from native_windows import overlap
    probes={s:[e for e in clients[s] if e['type']=='reach_probe'] for s in ('a','b')}
    native=overlap(probes['a'],probes['b'],match['start'],finish['finish']) if all(probes.values()) else None
    qualification=None;metrics=None
    for report in (ROOT/'experiments').glob('*.json'):
        document=json.loads(report.read_text())
        for result in document.get('results',[]):
            if result.get('id')==identifier and 'validation' in result:
                qualification=result['validation'];metrics=result.get('combat_metrics');break
        if qualification is not None:break
    if qualification is None:
        for report in (ROOT/'research').glob('*.json'):
            document=json.loads(report.read_text())
            for row in document.get('results',[]):
                result=row.get('game',{})
                if result.get('id')==identifier and 'validation' in result:
                    qualification=result['validation'];metrics=result.get('combat_metrics');break
            if qualification is not None:break
    data={'match':match,'result':finish,'ticks':ticks,'hits':[e for e in events if e['type']=='hit'],'clients':clients,
          'native_overlap':native,'combat_metrics':metrics,'qualification':qualification,
          'input_clock_verified':bool(strict and click_count),
          'ping_changes':[e for e in events if e['type']=='ping_change']}
    output=ROOT/'recordings'/f'{identifier}.html';output.parent.mkdir(exist_ok=True)
    payload=json.dumps(data,separators=(',',':')).replace('<','\\u003c')
    output.write_text(TEMPLATE.replace('__DATA__',payload))
    print(output)
    return output


TEMPLATE=r'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PvP Lab · recorded match</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#11151c;color:#e8edf5;font:15px system-ui,sans-serif}main{max-width:1140px;margin:auto;padding:28px}h1{font-size:25px;margin:6px 0}p{color:#acb9cb;line-height:1.5}.tag{font:12px ui-monospace,monospace;color:#a4bbcf;text-transform:uppercase;letter-spacing:2px}canvas{display:block;background:#18212c;width:100%;border:1px solid #33404e;border-radius:10px}#scene{height:390px}#graph{height:130px;margin-top:14px}section{display:flex;gap:20px;margin:16px 0;flex-wrap:wrap}.card{flex:1;min-width:220px;border:1px solid #33404e;padding:15px;border-radius:10px}.a{color:#75ddc9}.b{color:#f6b78e}strong{font-size:22px}.controls{display:flex;align-items:center;gap:12px;margin:18px 0}button,select{background:#253547;border:1px solid #46627c;border-radius:6px;color:inherit;padding:9px 12px}input{width:100%;accent-color:#75ddc9}.mono{font:13px ui-monospace,monospace}#scrub{display:block}footer{font-size:12px;color:#91a3b6;margin-top:20px}#time{min-width:130px}
</style>
<main><div class="tag" id="provenance">PvP Lab · native engine recording</div><h1 id="title"></h1>
<p id="subtitle"></p><section><div class="card a"><span id="aname"></span><br><strong id="ascore"></strong><div class="mono" id="astate"></div></div><div class="card b"><span id="bname"></span><br><strong id="bscore"></strong><div class="mono" id="bstate"></div></div></section>
<canvas id="scene" aria-label="Recorded player positions from above and from the side"></canvas>
<div class="controls"><button id="play">Play</button><select id="speed" aria-label="Playback speed"><option value="0.5">0.5×</option><option value="1" selected>1×</option><option value="2">2×</option><option value="4">4×</option><option value="8">8×</option></select><span id="time" class="mono"></span><span id="gap" class="mono"></span></div>
<input id="scrub" type="range" min="0" value="0" step="1" aria-label="Match time">
<canvas id="graph" aria-label="Accepted hits over time; click to seek"></canvas>
<p class="mono" id="actions"></p><p id="combos"></p><p id="sequences"></p><p id="opportunities"></p>
<footer>This is an analysis replay of recorded Minecraft positions, attacks and inputs. It is not Minecraft video. Circles show a 3-block horizontal reference distance, not a substituted reach test. The real engine performed all raycasts, damage checks, movement and knockback. Data is embedded; this file makes no network requests.</footer></main>
<script id="data" type="application/json">__DATA__</script>
<script>
'use strict';
const D=JSON.parse(document.getElementById('data').textContent),M=D.match,T=D.ticks,start=M.start,end=D.result.finish,duration=end-start;
const $=id=>document.getElementById(id), colors=['#75ddc9','#f6b78e'];
$('provenance').textContent=D.qualification?.passed?'PvP Lab · native engine · input and replication checks passed':D.input_clock_verified?'PvP Lab · preliminary recording · replication unqualified':'PvP Lab · historical preliminary recording · old input clock';
if(D.qualification?.issues?.length)$('provenance').textContent+=' · '+D.qualification.issues.join('; ');
if(D.native_overlap){const n=D.native_overlap;const describe=s=>`${s.toUpperCase()}: ${(100*n[s].one_sided_fraction_given_attacker_can_hit).toFixed(1)}% one-sided / ${(n[s].attacker_can_hit_ms/1000).toFixed(1)} s able to hit; longest continuous window ${(n[s].longest_one_sided_window_ms/1000).toFixed(2)} s`;$('opportunities').textContent=describe('a')+' · '+describe('b')+`. Each percentage divides time that only this player could hit by all time this player could hit. Both native client POVs use shared host timestamps. Paired coverage ${(100*n.paired_coverage_fraction).toFixed(1)}%.`;}
if(D.combat_metrics){const c=D.combat_metrics;const describe=s=>`${s.toUpperCase()}: longest sustained reach-denial combo ${((c[s].sustained_native_combo?.duration_ms||0)/1000).toFixed(2)} s; longest unanswered streak ${c[s].longest_unanswered_streak} hits`;$('combos').textContent=describe('a')+' · '+describe('b')+'. Final hit score is the primary performance metric. Sustained locks require at least three fresh contacts and no accepted return attack; brief both-out-of-range intervals are allowed.';}
if(D.combat_metrics?.a?.native_denial_contact_sequence){const describe=s=>{const v=D.combat_metrics[s].native_denial_contact_sequence;return `${s.toUpperCase()}: ${(v.duration_ms/1000).toFixed(2)} s denial contact sequence, ${v.fresh_contacts} fresh hits, largest hit gap ${v.max_contact_gap_ms===null?'unavailable':(v.max_contact_gap_ms/1000).toFixed(2)+' s'}`;};$('sequences').textContent=describe('a')+' · '+describe('b')+'. These sequences require continuous measured defender reach denial and no accepted return hit, with no maximum contact-gap cutoff. The strict combo above caps hit gaps at one second.';}
$('title').textContent=`${M.policy_a} vs ${M.policy_b}`;
$('subtitle').textContent=`${M.id} · Minecraft Java 1.8.9 · RTT ${M.ping_a}/${M.ping_b} ms · ${M.reaction_ms_a??150}/${M.reaction_ms_b??150} ms tactical reaction · ${M.scenario?`timed ${M.duration_limit_ms/1000}s scenario`:'first to 100 hits'}`;
$('aname').textContent=M.policy_a;$('bname').textContent=M.policy_b;$('scrub').max=duration;
let now=0,playing=false,previousFrame=performance.now();
function last(items,time){let lo=0,hi=items.length;while(lo<hi){let mid=(lo+hi)>>1;if(items[mid].time<=time)lo=mid+1;else hi=mid;}return items[Math.max(0,lo-1)];}
const obs={},clicks={},probes={};for(const s of ['a','b']){obs[s]=D.clients[s].filter(e=>e.type==='observation');clicks[s]=D.clients[s].filter(e=>e.type==='click');probes[s]=D.clients[s].filter(e=>e.type==='reach_probe');}
function context(id){const c=$(id),r=c.getBoundingClientRect(),ratio=devicePixelRatio||1;if(c.width!==Math.round(r.width*ratio)||c.height!==Math.round(r.height*ratio)){c.width=Math.round(r.width*ratio);c.height=Math.round(r.height*ratio);}const x=c.getContext('2d');x.setTransform(ratio,0,0,ratio,0,0);x.clearRect(0,0,r.width,r.height);return [x,r.width,r.height];}
function label(x,t,a,b,c='#92a7ba'){x.fillStyle=c;x.font='12px system-ui';x.fillText(t,a,b);}
function line(x,a,b,c,d,color,width=1){x.strokeStyle=color;x.lineWidth=width;x.beginPath();x.moveTo(a,b);x.lineTo(c,d);x.stroke();}
function render(){
 const time=start+now, tick=last(T,time),p=[tick.a,tick.b],hit=last(D.hits,time),scores=hit&&hit.time<=time?[hit.hits_a,hit.hits_b]:[0,0];
 const change=last(D.ping_changes||[],time),latency=change&&change.time<=time?change:M;
 $('subtitle').textContent=`${M.id} · Minecraft Java 1.8.9 · RTT ${latency.ping_a}/${latency.ping_b} ms · ${M.reaction_ms_a??150}/${M.reaction_ms_b??150} ms tactical reaction · ${M.scenario?`timed ${M.duration_limit_ms/1000}s scenario`:'first to 100 hits'}`;
 $('ascore').textContent=`${scores[0]} / 100`;$('bscore').textContent=`${scores[1]} / 100`;
 $('time').textContent=`${(now/1000).toFixed(1)} / ${(duration/1000).toFixed(1)} s`;
 const gap=Math.hypot(p[0].posX-p[1].posX,p[0].posZ-p[1].posZ);$('gap').textContent=`Horizontal gap ${gap.toFixed(2)} m`;
 for(const [i,s] of ['a','b'].entries()){const o=last(obs[s],time),live=o&&o.time<=time,control=live?(o.forward?'W down':'W released'):M['policy_'+s]==='human'?'Human inputs not logged':'Waiting';$(s+'state').textContent=`${control} · ${p[i].sprint?'server sprinting':'server not sprinting'} · height ${(p[i].posY-4).toFixed(2)} m`;}
 let [x,w,h]=context('scene'),cx=(p[0].posX+p[1].posX)/2,cz=(p[0].posZ+p[1].posZ)/2,scale=Math.min(34,(w-100)/Math.max(12,gap+8));
 const project=(a,b)=>[w/2+(a-cx)*scale,125+(b-cz)*scale];
 for(let a=Math.floor(cx-16);a<=cx+16;a++){const [u]=project(a,cz);line(x,u,28,u,245,'#243342');}
 for(let b=Math.floor(cz-4);b<=cz+4;b++){const [,v]=project(cx,b);if(v>25&&v<245)line(x,20,v,w-20,v,'#243342');}
 label(x,'FROM ABOVE · camera follows midpoint',16,20);
 p.forEach((v,i)=>{
  const [u,z]=project(v.posX,v.posZ);x.strokeStyle=colors[i]+'40';x.lineWidth=1;x.beginPath();x.arc(u,z,3*scale,0,Math.PI*2);x.stroke();
  const trail=T.filter(t=>t.time<=time&&t.time>=time-1000);x.strokeStyle=colors[i]+'80';x.lineWidth=2;x.beginPath();trail.forEach((t,j)=>{let q=t[i?'b':'a'],[a,b]=project(q.posX,q.posZ);j?x.lineTo(a,b):x.moveTo(a,b);});x.stroke();
  x.fillStyle=colors[i];x.fillRect(u-.3*scale,z-.3*scale,.6*scale,.6*scale);label(x,i?'B':'A',u-4,z-15,colors[i]);
  line(x,u,z,u+v.motionX*scale*5,z+v.motionZ*scale*5,colors[i],2);
 });
 line(x,14,260,w-14,260,'#334454');label(x,'SIDE VIEW · native knockback height',16,280);
 const ux=p[1].posX-p[0].posX,uz=p[1].posZ-p[0].posZ,d=Math.max(.001,gap),sideScale=Math.min(40,(w-140)/Math.max(12,gap+6));
 line(x,30,h-25,w-30,h-25,'#678354',2);
 p.forEach((v,i)=>{const distance=((v.posX-cx)*ux+(v.posZ-cz)*uz)/d,u=w/2+distance*sideScale,foot=h-25-(v.posY-4)*sideScale;x.fillStyle=colors[i];x.fillRect(u-.3*sideScale,foot-1.8*sideScale,.6*sideScale,1.8*sideScale);label(x,i?'B':'A',u-4,foot-1.8*sideScale-8,colors[i]);});
 let [g,gw,gh]=context('graph');label(g,'ACCEPTED HITS',12,17);
 for(let s=0;s<2;s++){g.strokeStyle=colors[s];g.lineWidth=2;g.beginPath();g.moveTo(10,gh-20);D.hits.forEach(e=>g.lineTo(10+(e.time-start)/duration*(gw-20),gh-20-e[s?'hits_b':'hits_a']/100*(gh-45)));g.stroke();}
 line(g,10+now/duration*(gw-20),24,10+now/duration*(gw-20),gh-14,'#ffffff',1);
 const message=[];for(const [i,s] of ['a','b'].entries()){const recent=clicks[s].filter(e=>e.time>time-1000&&e.time<=time).length,ray=last(probes[s],time),known=ray&&ray.time<=time&&time-ray.time<=150;message.push(`${s.toUpperCase()}: ${recent} clicks/s · native POV: ${known?(ray.in_range?'CAN hit':'cannot hit'):'unavailable'}`);} $('actions').textContent=message.join(' · ');
}
$('play').onclick=()=>{if(now>=duration)now=0;playing=!playing;$('play').textContent=playing?'Pause':'Play';};
$('scrub').oninput=()=>{now=Number($('scrub').value);render();};
$('graph').onclick=e=>{let r=$('graph').getBoundingClientRect();now=Math.max(0,Math.min(duration,(e.clientX-r.left-10)/(r.width-20)*duration));$('scrub').value=now;render();};
document.addEventListener('keydown',e=>{if(e.code==='Space'&&e.target.tagName!=='INPUT'){e.preventDefault();$('play').click();}});
function frame(t){const dt=t-previousFrame;previousFrame=t;if(playing){now=Math.min(duration,now+dt*Number($('speed').value));$('scrub').value=now;if(now===duration){playing=false;$('play').textContent='Play';}}render();requestAnimationFrame(frame);}requestAnimationFrame(frame);
</script></html>'''


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('match');export(parser.parse_args().match)
