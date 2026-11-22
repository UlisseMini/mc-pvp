"""Recover one-second player snapshots from local 1.8.9 practice captures."""
import argparse
import base64
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timezone
import gzip
import json
from pathlib import Path
import re
import struct
from uuid import UUID


DEFAULT = Path.home() / 'Library/Application Support/minecraft/config/pvp-practice/captures'
PART = re.compile(r'(?:-part(\d+))?\.jsonl\.gz$')
HEADER = re.compile(rb'"time_ms":(\d+)')
USER_NAME = re.compile(r'[A-Za-z0-9_]{1,16}\Z')
COLOR = re.compile(r'§.')
CHAT = b'S02PacketChat'
BOUNDARY = (b'S01PacketJoinGame', b'S07PacketRespawn')
ENTITY_PACKETS = (
    b'S38PacketPlayerListItem', b'S0CPacketSpawnPlayer', b'S13PacketDestroyEntities',
    b'S15PacketEntityRelMove', b'S16PacketEntityLook', b'S17PacketEntityLookMove',
    b'S18PacketEntityTeleport', b'S19PacketEntityHeadLook', b'S04PacketEntityEquipment',
    b'S1CPacketEntityMetadata',
)


def read_varint(data, pos):
    length = 0
    for shift in range(0, 35, 7):
        byte = data[pos]
        pos += 1
        length |= (byte & 127) << shift
        if not byte & 128:
            return length, pos
    raise ValueError('Invalid string length')


def read_string(data, pos):
    length, pos = read_varint(data, pos)
    return data[pos:pos + length].decode('utf-8'), pos + length


def chat_text(node):
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return ''.join(map(chat_text, node))
    if isinstance(node, dict):
        return node.get('text', '') + chat_text(node.get('extra', []))
    return ''


def match_marker(raw):
    message, _ = read_string(raw, 1)
    message = COLOR.sub('', chat_text(json.loads(message))).strip()
    if message.startswith('Protect your bed and destroy the enemy beds'):
        return 'start', None
    if message.startswith('Sending you to ') or message == 'Bed Wars':
        return 'end', None
    if message.endswith('FINAL KILL!') and USER_NAME.fullmatch(message.split()[0]):
        return 'eliminated', message.split()[0]
    return None, None


def player_snapshot(state):
    self_entity = state.get('self')
    if not self_entity or 'uuid' not in self_entity:
        return None
    players = {}
    for entity in [*state.get('entities', []), self_entity]:
        uid = entity.get('uuid', '')
        name = entity.get('name', '')
        if len(uid) != 36 or uid[14] != '4' or not USER_NAME.fullmatch(name):
            continue
        if entity.get('noClip') or entity.get('health', 20) <= 0:
            continue
        players[uid] = [name, *entity['pos'], entity['yaw'], entity['sneaking'],
                        entity.get('held_item_id', 0)]
    return [self_entity['uuid'], bool(state.get('entities_truncated')), players]


def skip_slot(raw, pos):
    item_id, = struct.unpack_from('>h', raw, pos)
    pos += 2
    if item_id < 0:
        return pos
    pos += 3  # count (byte) + damage (short)
    # Skip NBT tag if present (0x00 = TAG_End means no NBT)
    if pos < len(raw) and raw[pos] != 0:
        raise ValueError('NBT slot metadata unsupported in fast scanner')
    return pos + 1


def parse_metadata_sneak(raw, pos):
    """Scan all Protocol 47 DataWatcher entries for index 0 (entity flags, bit 1 = sneaking)."""
    sneak = None
    end = len(raw)
    while pos < end:
        item = raw[pos]
        pos += 1
        if item == 0x7F:
            break
        dtype = item >> 5
        index = item & 0x1F
        if dtype == 0:
            if pos >= end:
                break
            if index == 0:
                sneak = bool(raw[pos] & 2)
            pos += 1
        elif dtype == 1:
            pos += 2
        elif dtype in (2, 3):
            pos += 4
        elif dtype == 4:
            _, pos = read_string(raw, pos)
        elif dtype == 5:
            try:
                pos = skip_slot(raw, pos)
            except (ValueError, struct.error):
                break
        elif dtype in (6, 7):
            pos += 12
        else:
            break
    return sneak


class EntityPackets:
    """Track protocol 47 player entities when the snapshot hits its 128-entity cap."""

    def __init__(self):
        self.names = {}
        self.entities = {}

    def apply(self, raw):
        kind = raw[0]
        if kind in (0x01, 0x07):
            self.entities.clear()
            return
        value, pos = read_varint(raw, 1)
        if kind == 0x38 and value == 0:
            count, pos = read_varint(raw, pos)
            for _ in range(count):
                uid = UUID(bytes=raw[pos:pos + 16])
                name, pos = read_string(raw, pos + 16)
                if uid.version == 4 and USER_NAME.fullmatch(name):
                    self.names[str(uid)] = name
                properties, pos = read_varint(raw, pos)
                for _ in range(properties):
                    _, pos = read_string(raw, pos)
                    _, pos = read_string(raw, pos)
                    if raw[pos]:
                        _, pos = read_string(raw, pos + 1)
                    else:
                        pos += 1
                _, pos = read_varint(raw, pos)
                _, pos = read_varint(raw, pos)
                if raw[pos]:
                    _, pos = read_string(raw, pos + 1)
                else:
                    pos += 1
        elif kind == 0x0C:
            uid = UUID(bytes=raw[pos:pos + 16])
            if uid.version == 4:
                x, y, z, yaw, _, item = struct.unpack_from('>iiiBBh', raw, pos + 16)
                self.entities[value] = [str(uid), x, y, z, yaw, False, max(0, item)]
        elif kind == 0x13:
            for _ in range(value):
                entity_id, pos = read_varint(raw, pos)
                self.entities.pop(entity_id, None)
        elif value in self.entities:
            entity = self.entities[value]
            if kind in (0x15, 0x17):
                delta = struct.unpack_from('>bbb', raw, pos)
                entity[1:4] = [a + b for a, b in zip(entity[1:4], delta)]
            if kind in (0x16, 0x17):
                entity[4] = raw[pos + (3 if kind == 0x17 else 0)]
            if kind == 0x18:
                entity[1:5] = struct.unpack_from('>iiiB', raw, pos)
            if kind == 0x19:
                entity[4] = raw[pos]
            if kind == 0x04:
                slot, item = struct.unpack_from('>hh', raw, pos)
                if slot == 0:
                    entity[6] = max(0, item)
            if kind == 0x1C:
                sneak = parse_metadata_sneak(raw, pos)
                if sneak is not None:
                    entity[5] = sneak

    def supplement(self, snapshot, eliminated, count):
        own, truncated, players = snapshot
        if truncated:
            for uid, x, y, z, yaw, sneak, item in self.entities.values():
                name = self.names.get(uid)
                if name and uid != own and uid not in players and name not in eliminated:
                    players[uid] = [name, x / 32, y / 32, z / 32, yaw * 360 / 256, sneak, item]
                    count += 1
        for uid in list(players):
            if players[uid][0] in eliminated:
                del players[uid]
        return count


def discover(root):
    groups = {}
    for file in root.glob('a-client-*-events*.jsonl.gz'):
        match = PART.search(file.name)
        if match:
            groups.setdefault(file.name[:match.start()], {})[int(match[1] or '1')] = file
    sources = []
    for name, parts in sorted(groups.items()):
        if 1 not in parts:
            continue
        with gzip.open(parts[1], 'rb') as stream:
            hypixel = any(b'"type":"capture_start"' in line and b'"server_host":"hypixel.net"' in line
                          for _, line in zip(range(128), stream))
        if hypixel:
            sources.append([parts[i] for i in sorted(parts)])
    return sources


def extract(parts):
    origin = parts[0].name.split('-')[2]
    matches, active = [], None
    errors = []
    packets = EntityPackets()
    eliminated = set()
    for part in parts:
        try:
            with gzip.open(part, 'rb') as stream:
                for line in stream:
                    if line.startswith(b'{"type":"packet_wire"'):
                        is_chat = CHAT in line
                        is_world = any(kind in line for kind in BOUNDARY)
                        if not (is_chat or is_world or any(kind in line for kind in ENTITY_PACKETS)):
                            continue
                        encoded = json.loads(line)['data']['base64']
                        if not encoded:
                            continue
                        raw = base64.b64decode(encoded)
                        if not is_chat:
                            packets.apply(raw)
                            if not is_world:
                                continue
                        marker, name = match_marker(raw) if is_chat else ('world', None)
                        if marker is None:
                            continue
                        stamp = int(HEADER.search(line)[1])
                        if marker == 'start':
                            if active is not None:
                                active['ended_by'] = 'next_start'
                            active = {'capture': origin, 'start_ms': stamp, 'ended_by': 'capture_end',
                                      'frames': {}, 'packet_positions': 0}
                            eliminated.clear()
                            matches.append(active)
                        elif marker == 'eliminated':
                            if active is not None:
                                eliminated.add(name)
                        elif active is not None:
                            if marker == 'world' and stamp - active['start_ms'] < 3000:
                                continue  # Countdown-to-game teleport.
                            # S07 may be used for normal respawns. Chat handles transfers.
                            if marker == 'world' and b'S07PacketRespawn' in line:
                                continue
                            active['ended_by'] = marker
                            active = None
                    elif active is not None and line.startswith(b'{"type":"tick_end"'):
                        stamp = int(HEADER.search(line)[1])
                        # First complete tick at or after each scheduled second, with no gap fill.
                        second, delay = divmod(stamp - active['start_ms'], 1000)
                        if delay > 250 or second in active['frames']:
                            continue
                        snapshot = player_snapshot(json.loads(line)['data'])
                        if snapshot is not None:
                            active['packet_positions'] = packets.supplement(snapshot, eliminated,
                                                                             active['packet_positions'])
                            active['frames'][second] = snapshot
        except (OSError, EOFError) as exc:
            errors.append([part.name, type(exc).__name__])
    # Drop spawn handoff and the outermost second, where the next frame may be absent.
    for match in matches:
        frames = match['frames']
        end = max(frames, default=0)
        match['frames'] = {t: value for t, value in frames.items() if 2 <= t < end}
    print(f'{origin}: {len(parts)} parts, {len(matches)} starts, '
          f'{sum(len(m["frames"]) for m in matches)} frames, {len(errors)} read errors', flush=True)
    return matches, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--captures', type=Path, default=DEFAULT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--id-map', type=Path, help='Optional JSON map of match_key -> stable game ID')
    args = parser.parse_args()
    sources = discover(args.captures)
    if not sources:
        parser.error('No Hypixel capture sessions found')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    # Record file membership before reading because new parts can appear while Minecraft is open.
    manifest = [[file.name for file in files] for files in sources]
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        results = list(executor.map(extract, sources))
    matches = sorted((m for games, _ in results for m in games if m['frames']), key=lambda m: (m['start_ms'], m['capture']))
    fixed_ids = json.loads(args.id_map.read_text()) if args.id_map and args.id_map.is_file() else {}
    used_ids = set(fixed_ids.values())
    next_index = 1
    for match in matches:
        key = f"{match['capture']}:{match['start_ms']}"
        match['match_key'] = key
        if key in fixed_ids:
            match['id'] = fixed_ids[key]
        else:
            while f'game-{next_index:02}' in used_ids:
                next_index += 1
            match['id'] = f'game-{next_index:02}'
            used_ids.add(match['id'])
            next_index += 1
    with gzip.open(args.out, 'wt') as stream:
        json.dump({'created_utc': datetime.now(timezone.utc).isoformat(), 'source_files': manifest,
                   'read_errors': [error for _, errors in results for error in errors], 'matches': matches}, stream)
    print(f'Saved {len(matches)} BedWars matches to {args.out}')


if __name__ == '__main__':
    main()
