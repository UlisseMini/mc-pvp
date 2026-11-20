'use strict';
const $=id=>document.getElementById(id);
const nf=new Intl.NumberFormat(), pct=n=>Math.round(n*100)+'%';
const panelName=tag=>{
  const [type,index,part]=tag.split('-');
  if(type==='showcase')return 'Exhibition '+Number(index);
  const prefix='Season '+Number(index)+' · ';
  if(type==='scout')return prefix+'Scouting';
  if(type==='timing')return prefix+'Timing crossover';
  if(index==='014')return prefix+({a:'Earlier timing A',b:'Earlier timing B',c:'Active A',d:'Active B'}[part]);
  if(index==='028'&&part==='c')return prefix+'Direct challenge';
  if(index==='028'&&part==='d')return prefix+'Field challenge';
  return prefix+'Panel '+part.toUpperCase();
};
const elem=(tag,text)=>{const el=document.createElement(tag);el.textContent=text;return el};
let graphMode='model';
let state=null,selected=null,mode=null,page=0,current='',animation=0,trace=null,position=0,start=null,playing=false,loading=0,following=true;
const requested=new URLSearchParams(location.search).get('match');
const limit=12,film=$('film'),arena=$('arena');
const matches=()=>state.matches.filter(m=>{
 if($('panel').value&&m.tag!==$('panel').value)return false;
 switch($('type').value){
  case 'showcase':return m.video&&m.tag.startsWith('showcase-');
  case 'frames':return m.video&&Boolean(m.frameExport);
  case 'video':return m.video;
  case 'trace':return !m.video;
  default:return true;
 }
});
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
  $('format').textContent='JAVA CLIENT VIDEO';$('sourceNote').textContent=selected.frameExport?
   (selected.height?selected.height+'p · ':'')+'60 fps · Normal game speed · '+nf.format(selected.frameExport.count)+' frames rendered by Java on the scheduled clock':
   selected.fps+' fps capture · Normal speed'+(selected.rendered!=null?' · Client rendered '+selected.rendered+' fps median':selected.framePolicy==='afk'?' · Recorded with the original AFK limiter':'');
  if(selected.aimProfile==='smooth_mouse_v1')$('sourceNote').textContent+=' · Smooth mouse aim';
  if(selected.aimProfile==='perfect')$('sourceNote').textContent+=' · Perfect aim';
  film.onloadedmetadata=()=>{if(generation===loading && !selected.frameExport && film.duration>2)film.currentTime=Math.min(1.3,film.duration/4)};
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
 const was=selected;selected=m;if(!was||was.id!==m.id){$('shadows').checked=m.fps<60;$('shadows').onchange()}$('matchTitle').textContent=m.actor+' vs '+m.rival;
 const outcome=m.otherHP===0?m.actor+' won':m.hp===0?m.rival+' won':'Draw';
 $('matchMeta').textContent=panelName(m.tag)+' · Match '+m.number+' · '+m.rtt+' ms added RTT · '+outcome;
 $('switches').hidden=!m.video;$('map').hidden=m.trace===false;$('saveFilm').href='/video/'+m.id;$('saveFilm').download=m.id.replace('/','-')+'.mp4';
 if(reset)history.replaceState(null,'','?match='+encodeURIComponent(m.id)+'#matches');
 if(reset||!was||was.id!==m.id||!mode)setMode(m.video?'film':'map');
 showLibrary();if(graphMode==='java')chart();
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
function pairedScores(matches){
 const cell=m=>JSON.stringify([m.tag,m.rivalId,m.rtt,m.opening,m.seat]);
 const score=m=>m.otherHP===0?1:m.hp===0?0:.5;
 const control=new Map(matches.filter(m=>m.actor==='W-tap').map(m=>[cell(m),score(m)]));
 const byName=new Map();
 for(const m of matches){
  if(!control.has(cell(m)))continue;
  const a=byName.get(m.actor)||{points:0,baseline:0,n:0};
  a.points+=score(m);a.baseline+=control.get(cell(m));a.n++;byName.set(m.actor,a);
 }
 return name=>{const a=byName.get(name);return a?[a.points+'/'+a.n,a.baseline+'/'+a.n]:['Pending','Pending']};
}
function chart(){
 if(!state)return;
 const colors=['#b9eca7','#82c7ea','#d4a7f4','#f4998e','#c2d594','#9eacff'];
 let series=[],ticks=[],rows=[],heads=[];
 if(graphMode==='model'){
  const h=state.history.filter(v=>v.aim===state.aimProfile);
  series=[['Main fighter',h.map(v=>v.main),colors[0]],['W-tap',h.map(v=>v.wt),'#f3bd77']];
  ticks=['Season '+(h[0]?.n??'—'),'Season '+(h.at(-1)?.n??'—')];
  rows=h.map(v=>[v.n,pct(v.main),pct(v.wt)]);heads=['Season','Main fighter','W-tap'];
  $('chartTitle').textContent='Training performance';$('chartNote').textContent='Score across each season’s opponent field. The field grows as rivals join.';
 }else{
  const tag=selected?.tag||state.panel?.tag||state.matches[0]?.tag;if(!tag)return;
  const matches=state.matches.filter(m=>m.tag===tag).sort((a,b)=>a.number-b.number);
  const actors=new Map();for(const m of matches){const a=actors.get(m.actor)||{points:0,values:[]};a.points+=m.otherHP===0?1:m.hp===0?0:.5;a.values.push(a.points/(a.values.length+1));actors.set(m.actor,a)}
  const paired=pairedScores(matches);
  let n=0;for(const [name,a] of actors){series.push([name,a.values,name==='W-tap'?'#f3bd77':colors[n++%colors.length]]);rows.push([name,a.points+'/'+a.values.length,pct(a.values.at(-1)),...paired(name)])}
  ticks=['Bout 1','Bout '+Math.max(1,...series.map(v=>v[1].length))];heads=['Fighter','All bouts','Score rate','Paired','Paired W-tap'];
  $('chartTitle').textContent='Java performance';$('chartNote').textContent=panelName(tag)+' · Cumulative score per fighter across its played bouts. Draws count half.';
 }
 const c=$('history'),d=window.devicePixelRatio||1,rect=c.getBoundingClientRect(),w=rect.width,H=rect.height;if(w<1)return;
 c.setAttribute('aria-label',$('chartTitle').textContent);c.width=w*d;c.height=H*d;const g=c.getContext('2d');g.scale(d,d);
 const count=Math.max(1,...series.map(v=>v[1].length));const X=i=>42+i*(w-60)/Math.max(count-1,1),Y=v=>10+(1-v)*(H-39);
 g.font='12px system-ui';g.fillStyle='#a9b2ac';g.lineWidth=1;for(const n of [0,.25,.5,.75,1]){g.strokeStyle='#39443c';g.beginPath();g.moveTo(40,Y(n));g.lineTo(w-9,Y(n));g.stroke();g.fillText(pct(n),1,Y(n)+4)}
 const legend=$('legend');legend.replaceChildren();
 for(const [name,values,color] of series){g.strokeStyle=color;g.lineWidth=2.5;g.beginPath();values.forEach((v,i)=>i?g.lineTo(X(i),Y(v)):g.moveTo(X(i),Y(v)));g.stroke();const item=elem('span',name);item.style.setProperty('--swatch',color);legend.append(item)}
 g.textAlign='left';g.fillText(ticks[0],40,H-4);if(count>1){g.textAlign='right';g.fillText(ticks[1],w-7,H-4)}
 $('chartTable').replaceChildren(table(rows,heads));
}
for(const [id,value] of [['modelChart','model'],['javaChart','java']])$(id).onclick=()=>{graphMode=value;$('modelChart').classList.toggle('active',value==='model');$('javaChart').classList.toggle('active',value==='java');chart()};
function render(data){
 state=data;const panel=data.panel;$('status').textContent=data.status==='running'?(panel?'Java tournament':'Training'):'League finished';
 $('fullframes').hidden=!data.matches.some(m=>m.frameExport);
 $('now').textContent=panel?panelName(panel.tag)+' · '+panel.done+' of '+panel.total+' bouts complete':data.status==='running'?'League training is running.':'The league has completed its final challenge.';
 if(data.aimProfile==='perfect')$('now').textContent+=' · Perfect aim';
 $('champ').textContent=data.champion+(data.referencePending?' (reference)':'');$('seasons').textContent=data.season;$('training').textContent=nf.format(data.trainingGames);$('bouts').textContent=nf.format(data.matches.length);$('clips').textContent=nf.format(data.matches.filter(m=>m.video).length);
 const tags=[...new Set(data.matches.map(m=>m.tag))],old=$('panel').value;
 if(tags.join('|')!==$('panel').dataset.tags){$('panel').replaceChildren(new Option('All panels',''),...tags.map(t=>new Option(panelName(t),t)));$('panel').value=old;$('panel').dataset.tags=tags.join('|')}
 const rows=matches();
 if(!selected&&rows.length){
  const championDuel=m=>m.video&&m.actor!==m.rival&&m.actor!=='W-tap'&&(m.actor===data.champion||m.rival===data.champion);
  select(rows.find(m=>m.id===requested)||rows.find(m=>m.aimProfile===data.aimProfile&&championDuel(m)&&m.frameExport)||rows.find(m=>m.aimProfile===data.aimProfile&&m.frameExport&&m.actor!=='W-tap')||rows.find(m=>championDuel(m)&&m.frameExport)||rows.find(m=>m.frameExport&&m.actor!=='W-tap')||rows.find(m=>championDuel(m)&&m.fps===60&&m.rendered>=55)||
         rows.find(m=>championDuel(m)&&m.fps===60)||rows.find(championDuel)||rows.find(m=>m.video)||rows[0]);
 }
 else if(following&&selected&&!selected.video&&rows.some(m=>m.video)&&!playing)select(rows.find(m=>m.video));
 else showLibrary();
 chart();
 const live=$('liveScores');live.replaceChildren();
 if(panel){const arms=new Map(),games=data.matches.filter(m=>m.tag===panel.tag),paired=pairedScores(games);for(const m of games){const v=arms.get(m.actor)||[0,0];v[0]+=m.otherHP===0?1:m.hp===0?0:.5;v[1]++;arms.set(m.actor,v)}
  const section=elem('section','');section.append(elem('h3',panelName(panel.tag)+' · In progress'),table([...arms].sort((a,b)=>b[1][0]/b[1][1]-a[1][0]/a[1][1]).map(([name,[points,n]])=>[name,points+'/'+n,...paired(name)]),['Fighter','All bouts','Paired','Paired W-tap']));live.append(section)}
 const res=$('decisions');res.replaceChildren();
 for(const d of [...data.decisions].reverse()){const block=elem('section',''),wt=d.fighters.find(v=>v.name==='W-tap');block.append(elem('h3','Season '+d.season+(d.aim==='perfect'?' · Perfect aim':' · Original aim')+' · '+(d.promoted?'Promoted ':'Retained ')+d.champion),table(d.fighters.map(v=>[v.name,v.overall.join('/'),v.direct.join('/'),wt.overall.join('/')]),['Fighter','Field','vs incumbent','W-tap field']));res.append(block)}
}
async function refresh(){try{const r=await fetch('/api/league',{cache:'no-store'});if(!r.ok)throw Error(r.status);render(await r.json())}catch{$('status').textContent='Reconnecting';$('now').textContent='Waiting for the local league server.'}}
new ResizeObserver(chart).observe($('history'));refresh();setInterval(refresh,5000);
