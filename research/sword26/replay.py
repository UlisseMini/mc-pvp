"""Make an offline replay from the logged local and server observations."""
import csv
import json
from pathlib import Path
import sys

TEMPLATE = r"""<!doctype html><html lang="en"><meta charset="utf-8"><title>Sword duel replay</title>
<style>*{box-sizing:border-box}body{background:#172327;color:#f5f2e7;font:16px system-ui;max-width:920px;margin:24px auto;padding:0 16px}h1{font-size:24px}canvas{width:100%;background:#10191b;border-radius:8px}button,select{font:inherit;padding:.35em}input{width:100%}output{font-variant-numeric:tabular-nums;display:block;margin:.5em 0}</style>
<h1>Sword duel replay</h1><label>Round <select id="round"></select></label> <label>View <select id="view"><option>Server</option><option>Client A</option><option>Client B</option></select></label>
<canvas width="860" height="440"></canvas><input id="seek" type="range" value="0" min="0" step="1" aria-label="Replay tick">
<button id="play">Play</button> <label>Speed <select id="speed"><option>.25</option><option>.5</option><option selected>1</option><option>2</option></select></label><output id="status"></output>
<script>
const data=DATA, ctx=document.querySelector('canvas').getContext('2d'), el=id=>document.getElementById(id);
const colors=['#77d1e2','#ffb478'];let timer=0;
for(const id of Object.keys(data)){el('round').add(new Option(id,id))}
function text(s,x,y,color){ctx.fillStyle=color;ctx.font='16px system-ui';ctx.fillText(s,x,y)}
function frame(){let id=el('round').value,view=el('view').selectedIndex,rows=data[id][view],idx=Math.min(+el('seek').value,rows.length-1);
el('seek').max=Math.max(0,rows.length-1);ctx.fillStyle='#10191b';ctx.fillRect(0,0,860,440);
if(!rows.length){text('No client recording for this seat',20,40,'white');return}
const r=rows[idx];let line='';
if(!view){ctx.strokeStyle='#647a79';ctx.strokeRect(250,40,360,360);
for(let j=0;j<2;j++){let offset=3+j*3;ctx.fillStyle=colors[j];ctx.beginPath();ctx.arc(430+r[offset]*15,220+r[offset+2]*15,6,0,2*Math.PI);ctx.fill();
text((j?'B  ':'A  ')+r[1+j].toFixed(1)+' HP',j?630:80,100,colors[j])}}
else{let dx=r[4]-r[1],dz=r[6]-r[3],yaw=r[21]*Math.PI/180,pitch=r[22]*Math.PI/180;
let horizontal=-dx*Math.sin(yaw)+dz*Math.cos(yaw),side=dx*Math.cos(yaw)+dz*Math.sin(yaw),height=r[5]-r[2]+.9-1.62;
let depth=horizontal*Math.cos(pitch)-height*Math.sin(pitch);
ctx.strokeStyle='#536865';ctx.beginPath();ctx.moveTo(0,220-360*Math.tan(pitch));ctx.lineTo(860,220-360*Math.tan(pitch));ctx.stroke();
if(depth>.05){let scale=360/depth,cx=430-side*scale,cy=220-(height*Math.cos(pitch)+horizontal*Math.sin(pitch))*scale;
ctx.strokeStyle=colors[view===1?1:0];ctx.strokeRect(cx-.3*scale,cy-.9*scale,.6*scale,1.8*scale)}
ctx.fillStyle='white';ctx.fillRect(425,219,10,2);ctx.fillRect(429,215,2,10);
line=(r[16]>0?' W':r[16]<0?' S':'')+(r[17]>0?' A':r[17]<0?' D':'')+(r[18]?' Sprint':'')+(r[19]?' Jump':'')+(r[20]?' Attack':'');
text(r[13].toFixed(1)+' HP    '+Math.round(100*r[12])+'% charge',20,32,colors[view-1]);}
el('status').textContent='Tick '+r[0]+'   '+(r[0]/20).toFixed(2)+' s   '+line;}
function stop(){clearTimeout(timer);timer=0;el('play').textContent='Play'}
function advance(){if(+el('seek').value>=+el('seek').max){stop();return}el('seek').stepUp();frame();timer=setTimeout(advance,50/+el('speed').value)}
el('play').onclick=()=>{if(timer){stop();return}if(+el('seek').value>=+el('seek').max)el('seek').value=0;el('play').textContent='Pause';timer=setTimeout(advance,0)};
for(const id of ['round','view'])el(id).onchange=()=>{stop();el('seek').value=0;frame()};
el('seek').oninput=frame;frame();</script></html>"""


def build(folder):
    data={}
    for file in sorted(folder.glob("round-*-server.tsv")):
        key=file.name.removesuffix("-server.tsv")
        views=[]
        for suffix in ("server","A","B"):
            p=folder/f"{key}-{suffix}.tsv"
            if not p.exists():
                views.append([])
                continue
            with p.open() as f:
                rows=csv.reader(f,delimiter="\t");next(rows,None)
                views.append([[v=="true" if v in ("true","false") else float(v) for v in r] for r in rows])
        data[key]=views
    out=folder/"Replay.html"
    out.write_text(TEMPLATE.replace("DATA",json.dumps(data,separators=(",",":")),1))
    return out


if __name__=="__main__":
    print(build(Path(sys.argv[1])))
