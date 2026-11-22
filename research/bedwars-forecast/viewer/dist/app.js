import { Scene } from './scene.js';

const $ = id => document.getElementById(id);
const HORIZONS = [5, 10, 30, 60, 120];
const TITLES = {
  stay: 'Stay put', velocity: 'Constant velocity', drift: 'Learned drift', motion: 'Motion mixture',
  'motion-no-motion': 'Motion mixture, context only', 'motion-no-context': 'Motion mixture, motion only',
  'heatmap-ce': 'Heatmap, exact loss', heatmap: 'Multi-scale heatmap', 'head-tune': 'Ordinary head tuning',
  'anil-prior': 'ANIL prior', anil: 'ANIL adapted', history: 'Fixed history blend',
  'meta-history': 'Meta-learned history blend', 'personal-hotspots': 'Motion + personal hotspots',
  'personal-routes': 'Motion + personal routes'
};
const SCORE_MODELS = ['stay', 'motion', 'heatmap-ce', 'heatmap', 'head-tune', 'anil-prior', 'anil', 'history', 'meta-history'];
const state = { report: null, game: null, data: null, issue: 0, view: 'issue', arm: 'meta-history', horizon: 60, player: 0 };
const cache = new Map();
const expCode = Array.from({length: 16}, (_, i) => i === 0 ? 0 : Math.exp(.55 * (i - 15)));
const initial = new URLSearchParams(location.search);
let scene, request = 0;
let renderQueued = false;
let priorPair = 'meta-history';

function time(value) {
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`;
}

function option(value, name) {
  return new Option(name, value);
}

function labelPlayer(id) {
  if (id < 0) return 'All recorded players';
  return state.data?.poses.labels[id] ?? (id === 0 ? 'You' : `Player ${String(id).padStart(2, '0')}`);
}

function formatDelta(value, digits = 2) {
  if (!Number.isFinite(value)) return '–';
  return `${value < 0 ? '−' : '+'}${Math.abs(value).toFixed(digits)}`;
}

function trackURL() {
  const params = new URLSearchParams({ game: state.game, player: state.player, horizon: state.horizon,
    model: state.arm, t: state.issue, view: state.view });
  history.replaceState(null, '', `?${params}${location.hash}`);
}

async function asset(name, signal) {
  const response = await fetch('./assets/' + name, { signal });
  if (!response.ok) throw new Error(`Could not load ${name} (HTTP ${response.status})`);
  const original = await response.arrayBuffer();
  const bytes = new Uint8Array(original, 0, Math.min(2, original.byteLength));
  if (bytes[0] !== 31 || bytes[1] !== 139) return original;
  const uncompressed = new Blob([original]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Response(uncompressed).arrayBuffer();
}

async function jsonAsset(name, signal) {
  return JSON.parse(new TextDecoder().decode(await asset(name, signal)));
}

function records(buffer, poses, maxModels) {
  const view = new DataView(buffer);
  const magic = new TextDecoder().decode(new Uint8Array(buffer, 0, 4));
  const count = view.getUint32(4, true);
  const models = view.getUint16(8, true);
  const cells = view.getUint16(10, true);
  const width = models*cells/2;
  if (magic !== 'BWC1' || models !== maxModels || cells !== 256 || buffer.byteLength !== 12+count*(4+width)) {
    throw new Error('This forecast file is unavailable or incomplete.');
  }
  const tracks = new Map();
  const data = new Uint8Array(buffer, 12+4*count);
  for (let j = width; j < data.length; j++) data[j] = (data[j] + data[j-width]) & 255;
  for (let i = 0; i < count; i++) {
    const header = 12 + 4*i;
    const t = view.getUint16(header, true);
    const p = view.getUint8(header+2);
    const n = view.getUint8(header+3);
    if (!tracks.has(t)) tracks.set(t, new Map());
    tracks.get(t).set(p, { offset: i*width, support: n });
  }
  return { tracks, data, count, models, cells, poses };
}

function readMap(file, t, player, mode) {
  const collection = file.tracks.get(t);
  if (!collection) return null;
  const rows = player < 0 ? [...collection.values()] : [collection.get(player)].filter(Boolean);
  if (!rows.length) return null;
  const field = new Float32Array(file.cells);
  let count = 0;
  for (const record of rows) {
    const offset = record.offset + (mode*file.cells)/2;
    let total = 0;
    for (let cell = 0; cell < file.cells; cell += 2) {
      const byte = file.data[offset+cell/2];
      total += expCode[byte >> 4] + expCode[byte & 15];
    }
    for (let cell = 0; cell < file.cells; cell += 2) {
      const byte = file.data[offset+cell/2];
      field[cell] += expCode[byte >> 4]/(total*rows.length);
      field[cell+1] += expCode[byte & 15]/(total*rows.length);
    }
    count += record.support;
  }
  return { field, support: count/rows.length, players: rows.length };
}

function available(t) {
  const row = state.data?.pred.tracks.get(t);
  return state.player < 0 ? row?.size : row?.has(state.player);
}

function nearest(t) {
  const times = state.data ? [...state.data.pred.tracks.keys()].filter(available) : [];
  if (!times.length) return t;
  return times.reduce((best, v) => Math.abs(v-t) < Math.abs(best-t) ? v : best, times[0]);
}

function playersAt(t) {
  const list = state.data?.poses.frames[t] ?? [];
  return list.map(([id,x,y,z,yaw]) => ({ id, x,y,z,yaw, own:id===state.data.poses.own, name:labelPlayer(id) }));
}

function render(save = true) {
  if (!state.data) return;
  const poseTime = state.issue + (state.view === 'outcome' ? state.horizon : 0);
  $('timeline').value = state.issue;
  $('issue-clock').textContent = time(state.issue);
  $('frame-clock').textContent = time(poseTime);
  $('frame-mode').textContent = state.view === 'outcome' ? 'Recorded outcome' : 'Forecast time';
  $('issue').setAttribute('aria-pressed', String(state.view === 'issue'));
  $('compare').textContent = state.arm === 'anil-prior' || state.arm === 'heatmap' ? 'Show adapted model' : 'Show matching prior';
  const arm = state.report.models.indexOf(state.arm);
  const map = readMap(state.data.pred, state.issue, state.player, arm);
  const target = playersAt(state.issue + state.horizon);
  const current = playersAt(poseTime);
  scene?.frame(poseTime, current, state.player < 0 ? null : state.player, map?.field ?? null);
  $('stage-status').hidden = !!map && !!scene;
  if (!scene) $('stage-status').textContent = '3D replay requires WebGL. You can still read the results below.';
  else if (!map) $('stage-status').textContent = 'No forecast for this player at this second.';
  $('support-count').textContent = map ? state.player < 0 ? `${map.players} tracked subjects` :
    `${Math.round(map.support)} completed examples for this player` : '';
  const targetPlayer = target.find(p => p.id === state.player);
  let note = `At ${time(state.issue + state.horizon)}`;
  if (!map) note = 'Choose a nearby issue time with a recorded position.';
  else if (state.view === 'issue') note = `Outcome at ${time(state.issue + state.horizon)}. The overlay shows ${TITLES[state.arm].toLowerCase()} probability at that time.`;
  else if (state.player < 0) note += `, ${target.length} player positions were recorded.`;
  else if (targetPlayer) note += `, ${labelPlayer(state.player)} was at block ${[targetPlayer.x,targetPlayer.y,targetPlayer.z].map(Math.floor).join(', ')}.`;
  else note += `, the recording has no position for ${labelPlayer(state.player)}.`;
  $('outcome-note').textContent = note;
  $('outcome').disabled = !map;
  if (save) trackURL();
}

function queueRender(save = true) {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; render(save); });
}

async function getGame(game, h) {
  const key = `${game}/${h}`;
  if (!cache.has(key)) {
    if (cache.size >= 4) cache.delete(cache.keys().next().value);
    cache.set(key, Promise.all([jsonAsset(`poses-${game}.json.gz`), jsonAsset(`terrain-${game}.json.gz`),
                               asset(`${game}-${h}-view.bin.gz`)]).then(([poses, terrain, bytes]) =>
      ({ poses, terrain, pred: records(bytes, poses, state.report.models.length) })));
  }
  try { return await cache.get(key); }
  catch (error) { cache.delete(key); throw error; }
}

async function selectGame(game, h, fromStart = false) {
  const token = ++request;
  $('stage-status').hidden = false;
  $('stage-status').textContent = 'Loading arena and forecasts…';
  $('outcome').disabled = true;
  try {
    const value = await getGame(game, h);
    if (token !== request) return;
    const newGame = game !== state.game || !state.data;
    state.game = game;
    state.horizon = h;
    state.data = value;
    if (newGame) {
      scene?.load(value.terrain);
      $('player').replaceChildren(option(-1, 'All recorded players'),
        ...value.poses.labels.map((name,id) => option(id,name)));
      if (state.player >= value.poses.labels.length || !fromStart) state.player = value.poses.own ?? 0;
      $('player').value = state.player;
      if (!fromStart) state.issue = Math.floor(value.poses.end * .64);
    }
    $('timeline').min = value.poses.start;
    $('timeline').max = value.poses.end - h;
    state.issue = nearest(state.issue || Math.floor(value.poses.end*.64));
    $('replay-scope').textContent = `${value.poses.split === 'test' ? 'Held-out test' : 'Development'} · ${value.pred.count.toLocaleString()} forecasts at ${h}s`;
    render();
    renderResults();
  } catch (error) {
    if (token !== request) return;
    $('stage-status').hidden = false;
    $('stage-status').textContent = error.message;
    $('stage-status').setAttribute('role', 'alert');
  }
}

function mean(values) { return values.length ? values.reduce((a,b)=>a+b,0)/values.length : NaN; }

function numeric(model, horizon, cohort, metric) {
  const rows = state.report.scores.filter(r => r.split === 'test' && r.model === model && r.horizon === horizon && r.cohort === cohort);
  return mean(rows.map(r => r[metric]));
}

function element(tag, text, className) {
  const item = document.createElement(tag);
  if (text !== null) item.textContent = text;
  if (className) item.className = className;
  return item;
}

function table(node, headers, rows) {
  const head = element('thead', null);
  const hr = element('tr', null);
  for (const label of headers) {
    const heading = element('th', label);
    heading.scope = 'col';
    hr.append(heading);
  }
  head.append(hr);
  const body = element('tbody', null);
  for (const row of rows) {
    const tr = element('tr', null);
    const cells = row.map((entry, i) => {
      const value = typeof entry === 'string' ? {label:entry} : entry;
      const td = element(i ? 'td' : 'th', value.label, value.className);
      if (!i) td.scope = 'row';
      if (value.title) td.title = value.title;
      return td;
    });
    tr.append(...cells);
    body.append(tr);
  }
  node.replaceChildren(node.querySelector('caption') ?? element('caption', ''), head, body);
}

function renderResults() {
  if (!state.report) return;
  const cohort = $('cohort').value;
  const metric = $('resolution').value;
  const rows = SCORE_MODELS.map(m => [TITLES[m], ...HORIZONS.map(h => {
    const value = numeric(m,h,cohort,metric);
    const best = Math.min(...SCORE_MODELS.map(k => numeric(k,h,cohort,metric)).filter(Number.isFinite));
    return { label: Number.isFinite(value) ? value.toFixed(2) : '–', className: value === best ? 'best' : ''};
  })]);
  table($('score-table'), ['Model', ...HORIZONS.map(h => `${h}s`)], rows);
  const h = state.horizon;
  const comparisons = [
    ['ANIL', 'anil', 'anil-prior'], ['Meta history', 'meta-history', 'heatmap']
  ];
  const subtitle = `${h}s horizon · difference in log loss relative to each model’s own prior. Negative favors adaptation.`;
  $('adapt-description').textContent = subtitle;
  const diff = [];
  for (const g of state.report.games.filter(g => g.split === 'test')) {
    const inGame = state.report.scores.filter(r => r.game === g.id && r.horizon === h && r.cohort === cohort);
    const byModel = new Map(inGame.map(r => [r.model, r]));
    for (const [label, fit, prior] of comparisons) {
      if (byModel.has(fit) && byModel.has(prior)) diff.push([g.id, label, byModel.get(fit)[metric] - byModel.get(prior)[metric]]);
    }
  }
  const radius = Math.max(.08, ...diff.map(r => Math.abs(r[2])));
  $('adapt-bars').replaceChildren(...diff.map(([game,name,value]) => {
    const track = element('span', null, 'bar-track');
    track.setAttribute('aria-hidden','true');
    const fill = element('i', null, value > 0 ? 'bad' : 'good');
    const extent = Math.abs(value) / radius * 49;
    fill.style.width = extent+'%';
    fill.style.left = (value > 0 ? 50 : 50-extent)+'%';
    track.append(fill);
    const row = element('div', null, 'bar-row');
    row.append(element('span', game.replace('game-', '')+' · '+name), track,
               element('span', formatDelta(value), value <= 0 ? 'good' : 'bad'));
    return row;
  }));
  const playerRows = state.report.players.filter(r => r.game === state.game && r.horizon === h);
  const playerIds = [...new Set(playerRows.map(r=>r.player))].sort((a,b)=>a-b);
  $('player-description').textContent = `Match ${state.game?.split('-')[1] ?? '–'} · ${h}s · late-game rows with support.`;
  table($('player-table'), ['Player', 'Examples', 'ANIL Δ', 'Meta Δ'], playerIds.map(p => {
    const related = new Map(playerRows.filter(r=>r.player===p).map(r=>[r.model,r]));
    const fn = (a,b) => related.has(a) && related.has(b) ? related.get(a)[metric] - related.get(b)[metric] : NaN;
    const a = fn('anil','anil-prior'), m = fn('meta-history','heatmap');
    return [labelPlayer(p), String(related.get('heatmap')?.n ?? 0), {label:formatDelta(a),className:a<0?'good':'bad'},
            {label:formatDelta(m),className:m<0?'good':'bad'}];
  }));
  const best = HORIZONS.map(x => [x, ...['heatmap','motion'].map(m=>numeric(m,x,'all','block'))]);
  const msWins = best.filter(r=>r[1]<r[2]).length;
  const a60 = numeric('anil',60,'adapt','block')-numeric('anil-prior',60,'adapt','block');
  const m60 = numeric('meta-history',60,'adapt','block')-numeric('heatmap',60,'adapt','block');
  const winnerText = (delta, name) => `${name} changed its own prior’s 60s exact-block loss by ${formatDelta(delta)} nats.`;
  const callouts = [
    ['Location forecast', `The multi-scale heatmap beat the motion mixture on exact-block loss at ${msWins} of ${best.length} tested horizons.`],
    ['Within-match learning', winnerText(a60,'ANIL') + ' ' + winnerText(m60,'Meta history')]
  ];
  $('results-text').replaceChildren(...callouts.map(([title,copy]) => {
    const box = element('article', null);
    box.append(element('h3', title), element('p', copy));
    return box;
  }));
}

async function init() {
  try { scene = new Scene($('scene')); }
  catch (error) {
    $('stage-status').textContent = '3D replay requires WebGL. The benchmark is available below.';
    $('scene').setAttribute('aria-label', 'WebGL unavailable');
  }
  try {
    state.report = await jsonAsset('report.json');
    const selector = $('game');
    selector.replaceChildren(...['test','development'].map(split => {
      const group = document.createElement('optgroup');
      group.label = split === 'test' ? 'Held-out test' : 'Development';
      group.append(...state.report.games.filter(g=>g.split===split).map(g=>option(g.id,`Match ${g.id.split('-')[1]}`)));
      return group;
    }));
    $('horizon').replaceChildren(...HORIZONS.map(h=>option(h,`${h}s`)));
    $('model').replaceChildren(...state.report.models.map(m=>option(m,TITLES[m])));
    const validGame = state.report.games.find(g=>g.id===initial.get('game'))?.id;
    const defaultGame = state.report.games.find(g=>g.split==='test').id;
    const h = Number(initial.get('horizon'));
    state.horizon = HORIZONS.includes(h) ? h : 60;
    state.arm = state.report.models.includes(initial.get('model')) ? initial.get('model') : 'meta-history';
    state.player = Number(initial.get('player') ?? 0);
    if (!Number.isInteger(state.player) || state.player < -1) state.player = 0;
    state.issue = Math.min(60000,Math.max(0, Number(initial.get('t')) || 0));
    state.view = initial.get('view') === 'outcome' ? 'outcome' : 'issue';
    selector.value = validGame ?? defaultGame;
    $('horizon').value = state.horizon;
    $('model').value = state.arm;
    await selectGame(selector.value,state.horizon,true);
  } catch (error) {
    $('stage-status').textContent = error.message;
    $('stage-status').hidden = false;
  }
}

$('game').addEventListener('change', e => { state.view='issue'; selectGame(e.target.value,state.horizon); });
$('horizon').addEventListener('change', e => { state.view='issue'; selectGame($('game').value,Number(e.target.value),true); });
$('player').addEventListener('change', e => { state.player=Number(e.target.value); state.issue=nearest(state.issue); render(); });
$('model').addEventListener('change', e => { state.arm=e.target.value; render(); });
$('timeline').addEventListener('input', e => { state.issue=Number(e.target.value); state.view='issue'; queueRender(false); });
$('timeline').addEventListener('change', () => { state.issue=nearest(state.issue); render(); });
$('issue').addEventListener('click', () => { state.view='issue'; render(); });
$('outcome').addEventListener('click', () => { state.view='outcome'; render(); });
$('compare').addEventListener('click', () => {
  const toggles = {anil:'anil-prior','anil-prior':'anil'};
  if (state.arm in toggles) state.arm = toggles[state.arm];
  else if (state.arm === 'heatmap') state.arm = priorPair;
  else {
    priorPair = ['head-tune','history','meta-history'].includes(state.arm) ? state.arm : 'meta-history';
    state.arm = 'heatmap';
  }
  $('model').value = state.arm;
  render();
});
for (const [button, sign] of [['previous',-1],['next',1]]) {
  $(button).addEventListener('click', () => {
    const times = state.data ? [...state.data.pred.tracks.keys()].filter(t=>available(t) && sign*(t-state.issue)>0) : [];
    if (!times.length) return;
    state.issue = sign > 0 ? Math.min(...times) : Math.max(...times);
    state.view = 'issue';
    render();
  });
}
for (const button of document.querySelectorAll('[data-camera]')) {
  button.addEventListener('click', () => {
    scene?.preset(button.dataset.camera);
    for (const b of document.querySelectorAll('[data-camera]')) b.setAttribute('aria-pressed',String(b===button));
  });
}
for (const input of ['cohort','resolution']) $(input).addEventListener('change', renderResults);
init();
