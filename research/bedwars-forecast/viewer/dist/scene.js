import * as THREE from 'three';
import { OrbitControls } from './vendor/OrbitControls.js';

const WORLD_MIN = -128;
const TILE_SIDE = 16;
const GRID_SIZE = 16;
const DRAPE_STEP = 2;
const HEAT_MIN = 0.0012;
const HEAT_MAX = 0.2;
const UNIT = [0, 0, 0, 1, 1, 1];
const EMPTY_COLUMN = [];
const FACES = [
  { offset: [-1, 0, 0], corners: [[0, 0, 1], [0, 1, 1], [0, 1, 0], [0, 0, 0]], shade: .63 },
  { offset: [1, 0, 0], corners: [[1, 0, 0], [1, 1, 0], [1, 1, 1], [1, 0, 1]], shade: .82 },
  { offset: [0, -1, 0], corners: [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]], shade: .46 },
  { offset: [0, 1, 0], corners: [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]], shade: 1 },
  { offset: [0, 0, -1], corners: [[0, 0, 0], [0, 1, 0], [1, 1, 0], [1, 0, 0]], shade: .72 },
  { offset: [0, 0, 1], corners: [[1, 0, 1], [1, 1, 1], [0, 1, 1], [0, 0, 1]], shade: .88 },
];
const WOOL = ['e8e7db', 'ec7d34', 'bc5bb5', '60a8d8', 'eed257', '9dbd46', 'e28fae', '4e5058',
  '9d9c9b', '498b9b', '8c63b2', '475d9d', '78553b', '69834b', 'bd4c46', '35313b'];
const CLAY = ['d8b6a6', 'a75f42', '9e6a7f', '837a95', 'c89651', '777b4d', 'a56062', '504340',
  '8b7068', '62747b', '885b73', '575970', '6e5041', '58684c', '984c41', '473738'];
const WOOD = ['a58050', '786049', 'bda878', 'ad845f', 'b07750', '66503c'];
const COLORS = {
  1: '8d9297', 2: '7d694e', 3: '84664e', 4: '808c96', 5: 'aa8455', 7: '434753', 8: '4e86b6',
  9: '4e86b6', 10: 'e78537', 11: 'e78537', 12: 'd7c89c', 13: '96918b', 14: 'a7a699', 15: 'a89e96',
  16: '78777b', 17: '7b6245', 18: '5f864c', 19: 'b8ac52', 20: '7fb6c6', 21: '526981', 22: '325a8a',
  24: 'd4bd87', 25: '896347', 26: 'c94e4e', 31: '859d58', 32: '977150', 37: 'f1cb61', 38: 'e27d82',
  39: '94795e', 40: 'c47065', 41: 'edca66', 42: 'd3d6dc', 43: 'a7a9a5', 44: 'a7a9a5',
  45: 'ad6c61', 46: 'bc5948', 47: '9d7651', 48: '708c6a', 49: '494254', 50: 'd3a955',
  53: 'a58050', 54: 'a67a46', 56: '729da1', 57: '6fc9bf', 58: '9d7049', 60: '755f49',
  61: '7e8085', 62: '8c8581', 67: '808c96', 73: 'a67d82', 74: 'a67d82', 78: 'dfebf2', 79: '9bc6e5',
  80: 'e8eff0', 81: '639762', 82: '9baab7', 84: '947353', 85: 'a78253', 86: 'd29251', 87: '8e5b60',
  88: '795e52', 89: 'dec18a', 91: 'd59858', 92: 'e8cfb4', 95: '8cb0bd', 96: '9e784b', 97: '8d9297',
  98: '939ca4', 99: 'a9836b', 100: 'b77967', 101: '9ca6ac', 102: '8cb0bd', 103: 'a9ac5b', 106: '5b874e',
  107: 'a57f52', 108: 'ac756a', 109: '929aa1', 110: '8b7e91', 111: '598255', 112: '674451', 113: '674451',
  114: '694953', 116: '6c4f5c', 120: '9eaa85', 121: 'd0ca9a', 123: 'a18558', 124: 'dac17b', 125: 'ae875a',
  126: 'ad8658', 128: 'd4bd87', 129: '819993', 130: '365857', 133: '67bd87', 134: '786049', 135: 'bda878',
  136: 'ad845f', 139: '838e93', 145: '58646c', 152: 'ab5154', 153: 'a78a83', 155: 'ddd6ce', 156: 'ddd6ce',
  159: 'a86e59', 160: '9bb6c0', 161: '638c57', 162: '987555', 163: 'b07750', 164: '66503c', 165: '8ebf74',
  166: 'db7584', 167: 'a5a8ad', 168: '6faaa0', 169: 'c6dfd2', 170: 'c4aa5f', 172: 'af785f', 173: '4b5058',
  174: '78a6d2', 175: '829b59', 179: 'b77b51', 180: 'b77b51', 181: 'b77b51', 182: 'b77b51',
};
const GLASS = new Set([8, 9, 20, 79, 95, 102, 160, 165]);
const PLANTS = new Set([6, 30, 31, 32, 37, 38, 39, 40, 50, 51, 59, 63, 65, 68, 69, 70, 72, 75, 76,
  77, 83, 90, 93, 94, 104, 105, 106, 111, 115, 127, 131, 132, 140, 141, 142, 143, 147, 148, 149, 150,
  157, 171, 175, 176, 177]);
const STAIRS = new Set([53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180]);
const TOPS = [2, 17, 110, 162, 170];
const MARK_COLORS = [0x8ddcfe, 0xfdc18f, 0xdcaaff, 0xaada9e, 0xe5aabb, 0x9cdcdf, 0xdcd596, 0xacb7fa];
const SELECTED = 0x86ffef;
const HEAT_STOPS = [
  [0, 31, 83, 148], [.25, 64, 153, 246], [.5, 73, 234, 220], [.72, 246, 242, 144],
  [.88, 255, 176, 89], [1, 255, 104, 106],
];

function voxelKey(x, y, z) {
  return ((x + 512) * 1024 + z + 512) * 512 + y + 256;
}

function blockBoxes(state) {
  const id = state >> 4;
  const meta = state & 15;
  if (id === 44 || id === 126 || id === 182) return [meta & 8 ? [0, .5, 0, 1, 1, 1] : [0, 0, 0, 1, .5, 1]];
  if (STAIRS.has(id)) {
    const floor = meta & 4 ? [0, .5, 0, 1, 1, 1] : [0, 0, 0, 1, .5, 1];
    const stepStart = meta & 4 ? 0 : .5;
    const stepEnd = stepStart + .5;
    const step = [[.5, stepStart, 0, 1, stepEnd, 1], [0, stepStart, 0, .5, stepEnd, 1],
      [0, stepStart, .5, 1, stepEnd, 1], [0, stepStart, 0, 1, stepEnd, .5]][meta & 3];
    return [floor, step];
  }
  if (id === 26 || id === 92) return [[.04, 0, .04, .96, .57, .96]];
  if (id === 78) return [[0, 0, 0, 1, (1 + (meta & 7)) / 8, 1]];
  if (id === 85 || id === 113 || id === 138 || id === 139 || id >= 188 && id <= 197) return [[.32, 0, .32, .68, 1, .68]];
  if (id === 101 || id === 102 || id === 160) return [[.45, 0, 0, .55, 1, 1], [0, 0, .45, 1, 1, .55]];
  if (id === 171) return [[0, 0, 0, 1, .07, 1]];
  if (id === 8 || id === 9) return [[0, 0, 0, 1, .86, 1]];
  if (id === 140) return [[.28, 0, .28, .72, .43, .72]];
  if (PLANTS.has(id)) return [[.38, 0, .38, .62, .66, .62]];
  return [UNIT];
}

function transparent(state) {
  return GLASS.has(state >> 4);
}

function solidCube(state) {
  if (!state || transparent(state)) return false;
  const id = state >> 4;
  return !STAIRS.has(id) && !PLANTS.has(id) && ![26, 44, 78, 85, 92, 101, 113, 126, 138, 139, 140, 182].includes(id)
    && !(id >= 188 && id <= 197);
}

function styleOf(state, face) {
  const id = state >> 4;
  const data = state & 15;
  let color = COLORS[id] || '8e8586';
  let pattern = 'mineral';
  if (id === 35 || id === 171) return [WOOL[data], 'cloth'];
  if (id === 159) return [CLAY[data], 'clay'];
  if (id === 95 || id === 160) return [WOOL[data], 'glass'];
  if (GLASS.has(id)) return [color, 'glass'];
  if (id === 2 || id === 110) {
    if (face === 3) return [id === 2 ? '87a961' : '9c879d', 'leaves'];
    if (face !== 2) return [color, 'grassside'];
  }
  if (id === 18 || id === 161 || id === 31 || id === 175) return [color, 'leaves'];
  if (id === 17 || id === 162) return [face === 2 || face === 3 ? 'b29063' : color, face === 2 || face === 3 ? 'rings' : 'bark'];
  if (id === 5 || id === 125 || id === 126 || id === 85 || id === 107 || [53, 134, 135, 136, 163, 164].includes(id)) {
    const species = [5, 125, 126].includes(id) ? data & 7 : {134: 1, 135: 2, 136: 3, 163: 4, 164: 5}[id];
    return [WOOD[species] || color, 'planks'];
  }
  if ([4, 24, 45, 48, 98, 112, 155, 168, 179].includes(id)) pattern = 'brick';
  if ([41, 42, 57, 89, 133, 169].includes(id)) pattern = 'polished';
  if ([43, 44].includes(id)) {
    if ((data & 7) === 1) color = COLORS[24];
    if ((data & 7) === 4) color = COLORS[45];
    if ((data & 7) === 5) color = COLORS[98];
    if ((data & 7) === 6) color = COLORS[112];
    if ((data & 7) === 7) color = COLORS[155];
    pattern = 'polished';
  }
  return [color, pattern];
}

function tilePattern(key, u, v) {
  const noise = (((u * 17681 + v * 4969 + u * v * 353) ^ ((u << 3) + (v << 5))) & 31) / 31;
  switch (key) {
    case 'cloth': return (noise - .5) * .115 + (u % 2 === v % 2 ? .025 : -.025);
    case 'planks': return (noise - .5) * .10 - (v % 4 === 3 || ((u + 6 * (v >> 2)) & 15) === 0 ? .18 : 0);
    case 'bark': return (noise - .5) * .11 - (u % 4 === 0 ? .17 : 0);
    case 'rings': return (noise - .5) * .10 - (Math.max(Math.abs(u - 7.5), Math.abs(v - 7.5)) % 3 < 1 ? .17 : 0);
    case 'brick': return (noise - .5) * .14 - (v % 5 === 0 || (u + 4 * (v / 5 | 0)) % 9 === 0 ? .17 : 0);
    case 'polished': return (noise - .5) * .07 + ((u === 0 || v === 0) ? .09 : 0) - ((u === 15 || v === 15) ? .11 : 0);
    case 'grassside': return v < 3 + (u * 37 % 4 === 1 ? 1 : 0) ? [.86, 1.36, .71] : (noise - .5) * .22;
    case 'leaves': return (noise - .5) * .30;
    case 'glass': return u === 0 || u === 15 || v === 0 || v === 15 || u + v === 11 || u + v === 13 ? .22 : -.02;
    case 'clay': return (noise - .5) * .08;
    default: return (noise - .5) * .19;
  }
}

function makeAtlas(states) {
  const keys = new Map();
  const faces = new Map();
  for (const state of states) {
    faces.set(state, FACES.map((_, i) => {
      const [color, pattern] = styleOf(state, TOPS.includes(state >> 4) ? i : Math.min(i, 3));
      const key = `${color}/${pattern}`;
      if (!keys.has(key)) keys.set(key, { color, pattern, index: keys.size });
      return keys.get(key).index;
    }));
  }
  const tileSize = 20;
  const columns = Math.min(16, Math.max(1, keys.size));
  const rows = Math.max(1, Math.ceil(keys.size / columns));
  const canvas = document.createElement('canvas');
  canvas.width = columns * tileSize;
  canvas.height = rows * tileSize;
  const ctx = canvas.getContext('2d');
  const image = ctx.createImageData(canvas.width, canvas.height);
  for (const { color, pattern, index } of keys.values()) {
    const originX = (index % columns) * tileSize;
    const originY = Math.floor(index / columns) * tileSize;
    const rgb = color.match(/../g).map(x => parseInt(x, 16));
    for (let v = 0; v < tileSize; v++) for (let u = 0; u < tileSize; u++) {
      const px = Math.max(0, Math.min(15, u - 2));
      const py = Math.max(0, Math.min(15, v - 2));
      const modulation = tilePattern(pattern, px, py);
      const k = 4 * ((originY + v) * canvas.width + originX + u);
      for (let c = 0; c < 3; c++) image.data[k + c] = rgb[c] * (Array.isArray(modulation) ? modulation[c] : 1 + modulation);
      image.data[k + 3] = 255;
    }
  }
  ctx.putImageData(image, 0, 0);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestMipmapLinearFilter;
  const uv = index => {
    const x = (index % columns) * tileSize + 2;
    const y = Math.floor(index / columns) * tileSize + 2;
    return [x / canvas.width, 1 - (y + 16) / canvas.height, (x + 16) / canvas.width, 1 - y / canvas.height];
  };
  return { texture, faces, uv };
}

class MeshData {
  constructor() {
    this.positions = [];
    this.colors = [];
    this.uvs = [];
    this.triangles = [];
  }

  add(x, y, z, state, atlas, hidden) {
    const boxes = blockBoxes(state);
    const faceTiles = atlas.faces.get(state);
    for (const box of boxes) for (const [f, face] of FACES.entries()) {
      if (box === UNIT && hidden && hidden(f, face.offset)) continue;
      const index = this.positions.length / 3;
      const uv = atlas.uv(faceTiles[f]);
      for (const p of face.corners) {
        this.positions.push(x + box[p[0] * 3], y + box[1 + p[1] * 3], z + box[2 + p[2] * 3]);
        this.colors.push(face.shade, face.shade, face.shade);
      }
      const [u0, v0, u1, v1] = uv;
      this.uvs.push(u0, v0, u0, v1, u1, v1, u1, v0);
      this.triangles.push(index, index + 1, index + 2, index, index + 2, index + 3);
    }
  }

  geometry() {
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(this.positions, 3));
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(this.uvs, 2));
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(this.colors, 3));
    geometry.setIndex(this.triangles);
    geometry.computeBoundingSphere();
    return geometry;
  }
}

function lastAt(history, time) {
  let low = 0;
  let high = history.length;
  while (low < high) {
    const mid = (low + high) >>> 1;
    if (history[mid][0] <= time) low = mid + 1;
    else high = mid;
  }
  return low ? history[low - 1][1] : 0;
}

function decodeTerrain(data) {
  if (!data || data.version !== 1 || !Array.isArray(data.blocks) || !Array.isArray(data.changes)) {
    throw new Error('Invalid recorded terrain');
  }
  const staticBlocks = new Map();
  const dynamics = new Map();
  const states = new Set();
  const blocks = data.blocks;
  const changes = data.changes;
  for (let i = 0; i < changes.length; i += 5) {
    const [time, x, y, z, state] = changes.slice(i, i + 5);
    const key = voxelKey(x, y, z);
    if (!dynamics.has(key)) dynamics.set(key, { x, y, z, history: [] });
    dynamics.get(key).history.push([time, state]);
    if (state) states.add(state);
  }
  for (let i = 0; i < blocks.length; i += 5) {
    const [x, y, z, state, appear] = blocks.slice(i, i + 5);
    if (!state) continue;
    const key = voxelKey(x, y, z);
    const record = dynamics.get(key);
    if (record) record.history.unshift([Math.max(0, appear), state]);
    else staticBlocks.set(key, { x, y, z, state, appear: Math.max(0, appear) });
    states.add(state);
  }
  for (const record of dynamics.values()) record.history.sort((a, b) => a[0] - b[0]);
  return { staticBlocks, dynamics, states };
}

function heatColor(t) {
  const upper = HEAT_STOPS.findIndex(s => s[0] >= t);
  if (upper <= 0) return HEAT_STOPS[upper === 0 ? 0 : HEAT_STOPS.length - 1].slice(1);
  const lower = HEAT_STOPS[upper - 1];
  const higher = HEAT_STOPS[upper];
  const a = (t - lower[0]) / (higher[0] - lower[0]);
  return [1, 2, 3].map(i => Math.round(lower[i] * (1 - a) + higher[i] * a));
}

function makeHeatLayer() {
  const data = new Uint8Array(GRID_SIZE * GRID_SIZE * 4);
  const texture = new THREE.DataTexture(data, GRID_SIZE, GRID_SIZE);
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.minFilter = THREE.LinearFilter;
  texture.magFilter = THREE.LinearFilter;
  const positions = [];
  const uvs = [];
  const faces = [];
  const width = GRID_SIZE * TILE_SIDE;
  for (let x = 0; x < width; x += DRAPE_STEP) for (let z = 0; z < width; z += DRAPE_STEP) {
    const index = positions.length / 3;
    const side = DRAPE_STEP;
    positions.push(x + WORLD_MIN, 0, z + WORLD_MIN + side, x + WORLD_MIN + side, 0, z + WORLD_MIN + side,
      x + WORLD_MIN + side, 0, z + WORLD_MIN, x + WORLD_MIN, 0, z + WORLD_MIN);
    // The forecast array is row-major X then Z. Upload Z as the texture's horizontal axis.
    uvs.push((z + side) / width, x / width, (z + side) / width, (x + side) / width,
      z / width, (x + side) / width, z / width, x / width);
    faces.push(index, index + 1, index + 2, index, index + 2, index + 3);
  }
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3).setUsage(THREE.DynamicDrawUsage));
  geometry.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  geometry.setIndex(faces);
  const material = new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false,
    side: THREE.DoubleSide, toneMapped: false, polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -1 });
  const mesh = new THREE.Mesh(geometry, material);
  mesh.frustumCulled = false;
  mesh.renderOrder = 8;
  mesh.visible = false;
  return { mesh, texture, data };
}

function roundedRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.roundRect(x, y, w, h, r);
}

function makeMarker() {
  const canvas = document.createElement('canvas');
  canvas.width = 256;
  canvas.height = 104;
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const material = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false, depthWrite: false,
    sizeAttenuation: false });
  const sprite = new THREE.Sprite(material);
  sprite.center.set(.5, .13);
  sprite.renderOrder = 19;
  return { sprite, canvas, texture, material, label: undefined };
}

function mark(marker, name, color, selected) {
  const key = `${name || ''}/${color}/${selected}`;
  if (marker.label === key) return;
  marker.label = key;
  const ctx = marker.canvas.getContext('2d');
  ctx.clearRect(0, 0, 256, 104);
  const labelColor = `#${color.toString(16).padStart(6, '0')}`;
  const label = String(name || '').slice(0, 24);
  ctx.font = `${selected ? '600' : '500'} 22px system-ui, sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  if (label) {
    const width = Math.min(246, Math.ceil(ctx.measureText(label).width) + 28);
    roundedRect(ctx, 128 - width / 2, 19, width, 35, 9);
    ctx.fillStyle = selected ? 'rgba(12,24,33,0.97)' : 'rgba(12,24,33,0.83)';
    ctx.fill();
    ctx.lineWidth = selected ? 3 : 2;
    ctx.strokeStyle = labelColor;
    ctx.stroke();
    ctx.fillStyle = selected ? '#eafffc' : '#e7eff7';
    ctx.fillText(label, 128, 37, 222);
  }
  ctx.beginPath();
  ctx.moveTo(128, 83);
  ctx.lineTo(120, 64);
  ctx.lineTo(136, 64);
  ctx.closePath();
  ctx.fillStyle = labelColor;
  ctx.fill();
  marker.texture.needsUpdate = true;
}

function avatarPart(group, shape, material, scale, position) {
  const part = new THREE.Mesh(shape, material);
  part.scale.set(...scale);
  part.position.set(...position);
  group.add(part);
  return part;
}

function makeAvatar(cube) {
  const group = new THREE.Group();
  const shell = new THREE.MeshLambertMaterial({ color: 0x90beef });
  const skin = new THREE.MeshLambertMaterial({ color: 0xd8a27d });
  const trousers = new THREE.MeshLambertMaterial({ color: 0x39475e });
  avatarPart(group, cube, shell, [.56, .66, .32], [0, .99, 0]);
  avatarPart(group, cube, skin, [.54, .53, .54], [0, 1.59, 0]);
  avatarPart(group, cube, trousers, [.23, .65, .28], [-.14, .33, 0]);
  avatarPart(group, cube, trousers, [.23, .65, .28], [.14, .33, 0]);
  avatarPart(group, cube, shell, [.21, .65, .28], [-.405, .98, 0]);
  avatarPart(group, cube, shell, [.21, .65, .28], [.405, .98, 0]);
  const marker = makeMarker();
  marker.sprite.position.y = 2.1;
  group.add(marker.sprite);
  const ringMaterial = new THREE.MeshBasicMaterial({ color: SELECTED, transparent: true, opacity: .9,
    side: THREE.DoubleSide, depthWrite: false });
  const ring = new THREE.Mesh(new THREE.RingGeometry(.82, 1.03, 36), ringMaterial);
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = .08;
  ring.renderOrder = 16;
  group.add(ring);
  return { group, shell, skin, trousers, marker, ring, ringMaterial };
}

function disposeAvatar(avatar) {
  avatar.shell.dispose();
  avatar.skin.dispose();
  avatar.trousers.dispose();
  avatar.marker.texture.dispose();
  avatar.marker.material.dispose();
  avatar.ring.geometry.dispose();
  avatar.ringMaterial.dispose();
}

export class Scene {
  constructor(container) {
    this.container = container;
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x111f30);
    this.scene.fog = new THREE.FogExp2(0x142336, 0.00065);
    this.camera = new THREE.PerspectiveCamera(39, 1, .25, 1800);
    this.render = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' });
    this.render.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
    this.render.outputColorSpace = THREE.SRGBColorSpace;
    this.render.domElement.setAttribute('role', 'img');
    this.render.domElement.setAttribute('aria-label', '3D BedWars replay with a forecast heatmap');
    this.render.domElement.tabIndex = 0;
    this.render.domElement.style.display = 'block';
    this.render.domElement.style.width = '100%';
    this.render.domElement.style.height = '100%';
    this.render.domElement.style.touchAction = 'none';
    this.container.append(this.render.domElement);
    this.controls = new OrbitControls(this.camera, this.render.domElement);
    this.controls.listenToKeyEvents(this.render.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = .14;
    this.controls.minDistance = 3;
    this.controls.maxDistance = 970;
    this.controls.maxPolarAngle = 1.55;
    this.controls.zoomToCursor = true;
    this.controls.zoomSpeed = .9;
    this.controls.panSpeed = .84;
    this.controls.rotateSpeed = .64;
    this.controls.addEventListener('change', () => { this.dirty = true; });
    this.scene.add(new THREE.HemisphereLight(0xe4f3ff, 0x71747d, 1.85));
    const sun = new THREE.DirectionalLight(0xffe2bd, 1.55);
    sun.position.set(110, 160, 70);
    this.scene.add(sun);
    this.terrain = new THREE.Group();
    this.scene.add(this.terrain);
    this.avatars = new THREE.Group();
    this.scene.add(this.avatars);
    this.cube = new THREE.BoxGeometry(1, 1, 1);
    this.markers = new Map();
    this.heat = makeHeatLayer();
    this.scene.add(this.heat.mesh);
    this.terrainState = undefined;
    this.center = new THREE.Vector3(0, 73, 0);
    this.radius = 104;
    this.current = new Map();
    this.dirty = true;
    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(container);
    this.resize();
    this.preset('orbit');
    this.render.setAnimationLoop(() => {
      this.controls.update();
      if (!this.dirty) return;
      this.dirty = false;
      this.render.render(this.scene, this.camera);
    });
  }

  resize() {
    const width = this.container.clientWidth;
    const height = this.container.clientHeight;
    if (!width || !height) return;
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.render.setSize(width, height, false);
    this.dirty = true;
  }

  clearTerrain() {
    for (const child of this.terrain.children) child.geometry.dispose();
    if (this.terrainState) {
      this.terrainState.opaque.dispose();
      this.terrainState.translucent.dispose();
      this.terrainState.atlas.texture.dispose();
    }
    this.terrain.clear();
    this.terrainState = undefined;
  }

  load(data) {
    this.clearTerrain();
    for (const avatar of this.markers.values()) disposeAvatar(avatar);
    this.avatars.clear();
    this.markers.clear();
    this.current.clear();
    this.heat.mesh.visible = false;
    this.time = undefined;
    const { staticBlocks, dynamics, states } = decodeTerrain(data);
    const atlas = makeAtlas(states);
    const opaque = new THREE.MeshBasicMaterial({ map: atlas.texture, vertexColors: true, toneMapped: false });
    const translucent = new THREE.MeshBasicMaterial({ map: atlas.texture, vertexColors: true,
      transparent: true, opacity: .51, depthWrite: false, toneMapped: false });
    const bundles = new Map();
    const byColumn = new Map();
    const keyOfCol = (x, z) => (x + 512) * 1024 + z + 512;
    const remember = voxel => {
      const key = keyOfCol(voxel.x, voxel.z);
      if (!byColumn.has(key)) byColumn.set(key, []);
      byColumn.get(key).push(voxel);
    };
    for (const voxel of staticBlocks.values()) {
      const { x, y, z, state, appear } = voxel;
      const zone = `${appear}/${Math.floor(x / 64)}/${Math.floor(z / 64)}/${transparent(state)}`;
      if (!bundles.has(zone)) bundles.set(zone, { appear, glass: transparent(state), mesh: new MeshData() });
      bundles.get(zone).mesh.add(x, y, z, state, atlas, (_, offset) => {
        const n = staticBlocks.get(voxelKey(x + offset[0], y + offset[1], z + offset[2]));
        return n && n.appear <= appear && (solidCube(n.state) || transparent(state) && n.state === state);
      });
      if (!PLANTS.has(state >> 4)) remember(voxel);
    }
    const staticMeshes = [];
    for (const { appear, glass, mesh } of bundles.values()) {
      if (!mesh.triangles.length) continue;
      const object = new THREE.Mesh(mesh.geometry(), glass ? translucent : opaque);
      object.visible = false;
      if (glass) object.renderOrder = 4;
      object.matrixAutoUpdate = false;
      this.terrain.add(object);
      staticMeshes.push({ appear, object });
    }
    const dynamicMeshes = new Map();
    const maxByState = new Map();
    const tops = new Map([...states].map(state => [state, Math.max(...blockBoxes(state).map(box => box[4]))]));
    for (const voxel of dynamics.values()) {
      for (const id of new Set(voxel.history.map(([, state]) => state).filter(Boolean))) {
        maxByState.set(id, 1 + (maxByState.get(id) || 0));
      }
      remember(voxel);
    }
    for (const [id, capacity] of maxByState) {
      const mesh = new MeshData();
      mesh.add(0, 0, 0, id, atlas);
      const object = new THREE.InstancedMesh(mesh.geometry(), transparent(id) ? translucent : opaque, capacity);
      object.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
      object.count = 0;
      object.frustumCulled = false;
      if (transparent(id)) object.renderOrder = 4;
      this.terrain.add(object);
      dynamicMeshes.set(id, object);
    }
    for (const col of byColumn.values()) col.sort((a, b) => b.y - a.y);
    const ground = data.bounds;
    const halfX = Math.max(56, Math.min(128, (ground[3] - ground[0] + 1) / 2));
    const halfZ = Math.max(56, Math.min(128, (ground[5] - ground[2] + 1) / 2));
    const baseY = Number.isFinite(data.stats?.baseY) ? data.stats.baseY : ground[4] - 13;
    this.center.set((ground[0] + ground[3]) / 2, baseY, (ground[2] + ground[5]) / 2);
    this.radius = Math.hypot(halfX, halfZ) * .86;
    this.terrainState = { opaque, translucent, atlas, staticMeshes, dynamicMeshes, dynamics, byColumn, keyOfCol, baseY, tops };
    this.preset('orbit');
    this.setTerrainTime(0);
    this.dirty = true;
  }

  setTerrainTime(time) {
    if (!this.terrainState || this.time === time) return;
    this.time = time;
    const { staticMeshes, dynamicMeshes, dynamics } = this.terrainState;
    for (const { appear, object } of staticMeshes) object.visible = appear <= time;
    for (const mesh of dynamicMeshes.values()) mesh.count = 0;
    const translation = new THREE.Matrix4();
    for (const { x, y, z, history } of dynamics.values()) {
      const state = lastAt(history, time);
      if (!state) continue;
      const mesh = dynamicMeshes.get(state);
      mesh.setMatrixAt(mesh.count++, translation.makeTranslation(x, y, z));
    }
    for (const mesh of dynamicMeshes.values()) mesh.instanceMatrix.needsUpdate = true;
    if (this.heat.mesh.visible) this.drape();
    else this.heatTimestamp = undefined;
  }

  drape() {
    if (!this.terrainState || this.heatTimestamp === this.time) return;
    const { byColumn, keyOfCol, baseY, tops } = this.terrainState;
    const positions = this.heat.mesh.geometry.attributes.position;
    const width = GRID_SIZE * TILE_SIDE;
    const time = this.time;
    let i = 0;
    for (let x = 0; x < width; x += DRAPE_STEP) for (let z = 0; z < width; z += DRAPE_STEP) {
      let top = -Infinity;
      for (let dx = 0; dx < DRAPE_STEP; dx++) for (let dz = 0; dz < DRAPE_STEP; dz++) {
        const col = byColumn.get(keyOfCol(x + dx + WORLD_MIN, z + dz + WORLD_MIN));
        for (const voxel of col ?? EMPTY_COLUMN) {
          const state = voxel.history ? lastAt(voxel.history, time) : voxel.appear <= time ? voxel.state : 0;
          if (!state || PLANTS.has(state >> 4)) continue;
          top = Math.max(top, voxel.y + tops.get(state));
          break;
        }
      }
      const y = top === -Infinity ? baseY - 3 : top + .068;
      for (let j = 0; j < 4; j++) positions.setY(i++, y);
    }
    positions.needsUpdate = true;
    this.heatTimestamp = time;
  }

  setHeat(values) {
    this.heat.mesh.visible = !!values?.length;
    if (!this.heat.mesh.visible) return;
    if (values.length !== GRID_SIZE * GRID_SIZE) throw new Error('Expected a 16 × 16 probability grid');
    const { data, texture } = this.heat;
    for (let i = 0; i < values.length; i++) {
      const probability = values[i];
      const intensity = probability <= HEAT_MIN ? 0 : Math.min(1, Math.log(probability / HEAT_MIN) / Math.log(HEAT_MAX / HEAT_MIN));
      const [r, g, b] = heatColor(intensity);
      const k = i * 4;
      data[k] = r;
      data[k + 1] = g;
      data[k + 2] = b;
      data[k + 3] = Math.round(220 * Math.pow(intensity, .75));
    }
    texture.needsUpdate = true;
    this.drape();
  }

  frame(time, players, selected = null, overlay = null) {
    this.setTerrainTime(Math.floor(time));
    const current = new Map();
    for (const p of players || []) {
      if (![p.x, p.y, p.z].every(Number.isFinite)) continue;
      current.set(p.id, p);
      if (!this.markers.has(p.id)) {
        const avatar = makeAvatar(this.cube);
        this.avatars.add(avatar.group);
        this.markers.set(p.id, avatar);
      }
      const avatar = this.markers.get(p.id);
      const highlight = p.id === selected;
      const teamIndex = Number(p.team ?? p.id);
      const teamColor = Number.isFinite(p.color) ? p.color
        : MARK_COLORS[(Number.isFinite(teamIndex) ? Math.abs(teamIndex) : 0) % MARK_COLORS.length];
      const color = highlight ? SELECTED : p.own ? 0xebf6fb : teamColor;
      avatar.group.position.set(p.x, p.y, p.z);
      avatar.group.rotation.y = -THREE.MathUtils.degToRad(p.yaw || 0);
      avatar.group.visible = true;
      avatar.shell.color.setHex(color);
      avatar.ring.visible = highlight;
      avatar.marker.sprite.scale.set(highlight ? .214 : .155, highlight ? .086 : .063, 1);
      mark(avatar.marker, p.name ?? String(p.id), color, highlight);
    }
    for (const [id, avatar] of this.markers) if (!current.has(id)) avatar.group.visible = false;
    this.current = current;
    this.selected = selected;
    if (this.cameraMode === 'follow') this.follow();
    this.setHeat(overlay);
    this.dirty = true;
  }

  follow() {
    const person = this.current.get(this.selected) || [...this.current.values()].find(p => p.own) || this.current.values().next().value;
    if (!person) return;
    const target = new THREE.Vector3(person.x, person.y + 1.5, person.z);
    const delta = target.clone().sub(this.controls.target);
    this.camera.position.add(delta);
    this.controls.target.copy(target);
  }

  preset(name) {
    this.cameraMode = name;
    if (name === 'follow' && this.current.size) {
      this.follow();
      const target = this.controls.target;
      this.camera.position.set(target.x + 37, target.y + 31, target.z + 45);
    } else {
      const target = this.center;
      const angle = THREE.MathUtils.degToRad(this.camera.fov / 2);
      const distance = this.radius / Math.tan(angle) * Math.max(1, 1 / this.camera.aspect);
      this.controls.target.copy(target);
      if (name === 'top') this.camera.position.set(target.x, target.y + distance * .98, target.z + .003);
      else this.camera.position.copy(target).add(new THREE.Vector3(.64, .76, 1).normalize().multiplyScalar(distance * 1.04));
    }
    this.controls.update();
    this.dirty = true;
  }

  dispose() {
    this.resizeObserver.disconnect();
    this.render.setAnimationLoop(null);
    this.clearTerrain();
    for (const avatar of this.markers.values()) disposeAvatar(avatar);
    this.cube.dispose();
    this.heat.mesh.geometry.dispose();
    this.heat.mesh.material.dispose();
    this.heat.texture.dispose();
    this.controls.dispose();
    this.render.dispose();
    this.render.domElement.remove();
  }
}
