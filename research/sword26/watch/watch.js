'use strict';
const $=id=>document.getElementById(id);
const nf=new Intl.NumberFormat(), pct=n=>Math.round(n*100)+'%';
const panelName=tag=>{const s=tag.split('-');return s[0]==='showcase'?'Exhibition '+Number(s[1]):'Season '+Number(s[1])+' · '+(s[0]==='scout'?'Scouting':s[2]==='a'?'Panel A':'Panel B')};
const elem=(tag,text)=>{const el=document.createElement(tag);el.textContent=text;return el};
let state=null,selected=null,mode=null,page=0,current='',animation=0,trace=null,position=0,start=null,playing=false,loading=0,following=true;
const requested=new URLSearchParams(location.search).get('match');
const limit=12,film=$('film'),arena=$('arena');
const matches=()=>state.matches.filter(x=>(!$('panel').value||x.tag===$('panel').value)&&(!$('type').value||($('type').value==='video')===x.video));
function showLibrary(){
 const items=matches();page=Math.max(0,Math.min(page,Math.max(0,Math.ceil(items.length/limit)-1)));
 $('count').textContent=nf.format(items.length);$('page').textContent=(items.length?page+1:0)+' / '+Math.ceil(items.length/limit);
 $('prev').disabled=page===0;$('next').disabled=(page+1)*limit>=items.length;
 const wrap=$('list');wrap.replaceChildren();
 for(const m of items.slice(page*limit,(page+1)*limit)){
  const b=elem('button','');b.className='bout'+(selected?.id===m.id?' active':'');
  b.append(elem('strong',m.actor+' vs '+m.rival),elem('small',(m.video?'Java POV':'Arena replay')+' · '+panelName(m.tag)+' · #'+m.number+' · '+m.rtt+' ms'));
  b.onclick=()=>{following=false;select(m,true)};wrap.append(b);
 }
 if(!items.length)wrap.append(elem('p','No recordings match these filters.'));
}
$('shadows').onchange=()=>film.classList.toggle('lift',$('shadows').checked);$('shadows').onchange();
function stopTrace(){cancelAnimationFrame(animation);playing=false;start=null;$('play').textContent='Play'}
function setMode(next){
 if(!selected || (next===mode&&current===selected.id))return;
 const generation=++loading;current=selected.id;mode=next;stopTrace();trace=null;position=0;
 film.pause();film.removeAttribute('src');film.load();film.hidden=true;arena.hidden=true;$('replayControls').hidden=true;
 $('empty').hidden=false;$('empty').textContent='Loading…';
 $('pov').className=next==='film'?'active':'';$('map').className=next==='map'?'active':'';
 $('shadowLabel').hidden=next!=='film';
 if(next==='film'){
  $('format').textContent='JAVA CLIENT VIDEO';$('sourceNote').textContent=selected.fps+' fps capture · Original player view · Normal speed';
  film.onloadedmetadata=()=>{if(generation===loading && film.duration>2)film.currentTime=Math.min(1.3,film.duration/4)};
  film.src='/video/'+selected.id;film.playbackRate=1;film.hidden=false;$('empty').hidden=true;
  film.onerror=()=>{if(generation===loading){$('empty').hidden=false;$('empty').textContent='Video unavailable. Choose Arena replay below.'}};
 }else{
  $('format').textContent='ARENA REPLAY';$('sourceNote').textContent='Recorded Java positions and hits · Motion interpolated between server ticks';
  fetch('/trace/'+selected.id).then(r=>{if(!r.ok)throw Error(r.status);return r.json()}).then(data=>{
   if(generation!==loading)return;trace=data;arena.hidden=false;$('empty').hidden=true;$('replayControls').hidden=false;draw();
  }).catch(()=>{if(generation===loading)$('empty').textContent='This replay could not be loaded.'});
 }
}
function select(m,reset=false){
 const was=selected;selected=m;$('matchTitle').textContent=m.actor+' vs '+m.rival;
 const outcome=m.otherHP===0?m.actor+' won':m.hp===0?m.rival+' won':'Draw';
 $('matchMeta').textContent=panelName(m.tag)+' · Match '+m.number+' · '+m.rtt+' ms added RTT · '+outcome;
 $('switches').hidden=!m.video;$('map').hidden=m.trace===false;$('saveFilm').href='/video/'+m.id;$('saveFilm').download=m.id.replace('/','-')+'.mp4';
 if(reset)history.replaceState(null,'','?match='+encodeURIComponent(m.id)+'#matches');
 if(reset||!was||was.id!==m.id||!mode)setMode(m.video?'film':'map');
 showLibrary();
}
function draw(now){
 if(!trace)return;const frames=trace.frames;const end=frames[frames.length-1][0];
 if(playing){if(start===null)start=now-position;position=Math.min(end,now-start);if(position>=end)stopTrace()}
 let lo=0,hi=frames.length-1;while(lo<hi){const m=(lo+hi+1)>>1;if(frames[m][0]<=position)lo=m;else hi=m-1}
 const a=frames[lo],b=frames[Math.min(lo+1,frames.length-1)],t=Math.max(0,Math.min(1,(position-a[0])/(b[0]-a[0]||1)));
 const v=a.map((n,i)=>n+(b[i]-n)*t),ctx=arena.getContext('2d'),w=arena.width,h=arena.height;
 ctx.fillStyle='#13201d';ctx.fillRect(0,0,w,h);
 const bounds=12,pad=47,z=(h-2*pad)/(2*bounds);const X=x=>w/2+x*z,Y=y=>h/2+y*z;
 ctx.fillStyle='#1e3029';ctx.fillRect(X(-bounds),Y(-bounds),24*z,24*z);
 ctx.strokeStyle='#31483c';ctx.lineWidth=1;for(let n=-bounds;n<=bounds;n+=2){ctx.beginPath();ctx.moveTo(X(n),Y(-bounds));ctx.lineTo(X(n),Y(bounds));ctx.moveTo(X(-bounds),Y(n));ctx.lineTo(X(bounds),Y(n));ctx.stroke()}
 const colors=['#b9eca7','#efb47d'];const hp=[a[7],a[8]];
 for(let side=0;side<2;side++){
  const i=1+3*side,x=X(v[i]),y=Y(v[i+2]);
  ctx.beginPath();ctx.moveTo(X(frames[Math.max(0,lo-10)][i]),Y(frames[Math.max(0,lo-10)][i+2]));
  for(let j=Math.max(0,lo-9);j<=lo;j++)ctx.lineTo(X(frames[j][i]),Y(frames[j][i+2]));
  ctx.strokeStyle=colors[side];ctx.globalAlpha=.33;ctx.lineWidth=5;ctx.stroke();ctx.globalAlpha=1;
  ctx.fillStyle=colors[side];ctx.beginPath();ctx.arc(x,y,9,0,Math.PI*2);ctx.fill();
  ctx.font='600 18px system-ui';ctx.textAlign=side?'right':'left';ctx.fillText(side?'Seat B':'Seat A',side?w-23:23,32);
  ctx.fillText(hp[side].toFixed(1)+' HP',side?w-23:23,59);
 }
 let event=null;for(const e of trace.hits){if(e[0]>position)break;if(position-e[0]<480)event=e}
 if(event){ctx.textAlign='center';ctx.fillStyle='#eef2e9';ctx.font='600 20px system-ui';ctx.fillText('Seat '+event[1]+(event[2]?' · Critical hit':' · Hit'),w/2,h-15)}
 $('seek').value=end?Math.round(1000*position/end):0;$('time').textContent=(position/1000).toFixed(1)+'s / '+(end/1000).toFixed(1)+'s';
 if(playing)animation=requestAnimationFrame(draw);
}
$('play').onclick=()=>{if(!trace)return;if(playing){stopTrace();return}if(position>=trace.frames.at(-1)[0])position=0;playing=true;start=null;$('play').textContent='Pause';animation=requestAnimationFrame(draw)};
$('seek').oninput=()=>{if(!trace)return;stopTrace();position=trace.frames.at(-1)[0]*Number($('seek').value)/1000;draw()};
$('pov').onclick=()=>setMode('film');$('map').onclick=()=>setMode('map');
$('prev').onclick=()=>{page--;following=false;showLibrary()};$('next').onclick=()=>{page++;following=false;showLibrary()};
for(const id of ['panel','type'])$(id).onchange=()=>{following=false;page=0;const rows=matches();if(rows.length&&!rows.some(r=>r.id===selected?.id))select(rows[0]);else showLibrary()};
function table(rows,heads){const t=elem('table','');const thead=t.createTHead().insertRow();for(const x of heads)thead.append(elem('th',x));
 const body=t.createTBody();for(const r of rows){let tr=body.insertRow();for(const x of r)tr.append(elem('td',x))}return t}
function chart(){
 if(!state)return;const h=state.history,c=$('history'),d=window.devicePixelRatio||1,rect=c.getBoundingClientRect(),w=rect.width,H=rect.height;if(w<1)return;
 c.width=w*d;c.height=H*d;const g=c.getContext('2d');g.scale(d,d);const X=i=>42+i*(w-60)/Math.max(h.length-1,1),Y=v=>10+(1-v)*(H-39);
 g.font='12px system-ui';g.fillStyle='#a9b2ac';g.lineWidth=1;for(const n of [0,.25,.5,.75,1]){g.strokeStyle='#39443c';g.beginPath();g.moveTo(40,Y(n));g.lineTo(w-9,Y(n));g.stroke();g.fillText(pct(n),1,Y(n)+4)}
 for(const [key,color] of [['main','#b9eca7'],['wt','#f3bd77']]){g.strokeStyle=color;g.lineWidth=2.5;g.beginPath();h.forEach((r,i)=>i?g.lineTo(X(i),Y(r[key])):g.moveTo(X(i),Y(r[key])));g.stroke()}
 g.textAlign='left';g.fillText('Season 1',40,H-4);if(h.length>1){g.textAlign='right';g.fillText('Season '+h.at(-1).n,w-7,H-4)}
}
function render(data){
 state=data;const panel=data.panel;$('status').textContent=data.status==='running'?(panel?'Java tournament':'Training'):'League finished';
 $('now').textContent=panel?panelName(panel.tag)+' · '+panel.done+' of '+panel.total+' bouts complete':data.status==='running'?'League training is running.':'The league has completed its final challenge.';
 $('champ').textContent=data.champion;$('seasons').textContent=data.season;$('training').textContent=nf.format(data.trainingGames);$('bouts').textContent=nf.format(data.matches.length);$('clips').textContent=nf.format(data.matches.filter(m=>m.video).length);
 const tags=[...new Set(data.matches.map(m=>m.tag))],old=$('panel').value;
 if(tags.join('|')!==$('panel').dataset.tags){$('panel').replaceChildren(new Option('All panels',''),...tags.map(t=>new Option(panelName(t),t)));$('panel').value=old;$('panel').dataset.tags=tags.join('|')}
 const rows=matches();
 if(!selected&&rows.length)select(rows.find(m=>m.id===requested)||rows.find(m=>m.video&&m.actor!==m.rival&&m.actor!=='W-tap'&&(m.actor===data.champion||m.rival===data.champion))||rows.find(m=>m.video)||rows[0]);
 else if(following&&selected&&!selected.video&&rows.some(m=>m.video)&&!playing)select(rows.find(m=>m.video));
 else showLibrary();
 chart();$('chartTable').replaceChildren(table(data.history.map(v=>[v.n,pct(v.main),pct(v.wt)]),['Season','Main fighter','W-tap']));
 const live=$('liveScores');live.replaceChildren();
 if(panel){const arms=new Map();for(const m of data.matches){if(m.tag!==panel.tag)continue;const v=arms.get(m.actor)||[0,0];v[0]+=m.otherHP===0?1:m.hp===0?0:.5;v[1]++;arms.set(m.actor,v)}
  const section=elem('section','');section.append(elem('h3',panelName(panel.tag)+' · In progress'),table([...arms].sort((a,b)=>b[1][0]/b[1][1]-a[1][0]/a[1][1]).map(([name,[points,n]])=>[name,points+'/'+n]),['Fighter','Score / played']));live.append(section)}
 const res=$('decisions');res.replaceChildren();
 for(const d of [...data.decisions].reverse()){const block=elem('section','');block.append(elem('h3','Season '+d.season+' · '+(d.promoted?'Promoted ':'Retained ')+d.champion),table(d.fighters.map(v=>[v.name,v.overall.join('/'),v.direct.join('/')]),['Fighter','Field','vs incumbent']));res.append(block)}
}
async function refresh(){try{const r=await fetch('/api/league',{cache:'no-store'});if(!r.ok)throw Error(r.status);render(await r.json())}catch{$('status').textContent='Reconnecting';$('now').textContent='Waiting for the local league server.'}}
new ResizeObserver(chart).observe($('history'));refresh();setInterval(refresh,5000);
