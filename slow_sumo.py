"""Start or change the speed of the separate local Forge sumo training room."""
import argparse
import asyncio
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import lab_runtime as lab

ROOM = lab.ROOT / 'runtime/slow-sumo'
DATA = ROOM / 'runtime'
ADDRESS = '127.0.0.1:25585'


def manager():
    try:
        state = json.loads((DATA / 'manager.json').read_text())
        if state.get('address') != ADDRESS or 'training_speed' not in state:
            raise RuntimeError('Unexpected manager in the training directory')
        os.kill(state['pid'], 0)
        return state
    except (FileNotFoundError, ProcessLookupError):
        return None


def ready(pid):
    state = manager()
    if not state or state['pid'] != pid:
        return False
    try:
        status = json.loads((DATA / 'status.json').read_text())
        return all('LabSlot'+str(i) in status.get('training_ready', []) and
                   json.loads((DATA/f'bots/{i}/heartbeat.json').read_text())['time'] >= 1000*state['started']
                   for i in range(state['bot_slots']))
    except (OSError, KeyError, ValueError):
        return False


def change(speed, stop):
    state = manager()
    if state and not stop and state['training_speed'] == speed:
        deadline = time.monotonic() + 180
        while manager() and time.monotonic() < deadline:
            if ready(state['pid']):
                print(f'Slow sumo is ready at {ADDRESS} ({speed:g}×).')
                return
            time.sleep(.5)
        if manager():
            raise SystemExit('The training clients have not synchronized. See '+str(ROOM/'launcher.log'))
        state=None
    if state:
        if json.loads((DATA/'status.json').read_text())['matches']:
            raise SystemExit('Finish the active slow sumo duel or use /stop before changing the room.')
        lab.RUNTIME = DATA
        lab.send('shutdown')
        deadline = time.monotonic() + 40
        while manager() and time.monotonic() < deadline:
            time.sleep(.3)
        if manager():
            raise SystemExit('The training room is still shutting down.')
    if stop:
        print('Slow sumo is stopped.')
        return
    for name in ('policies', 'research'):
        link = ROOM / name
        if not link.exists():
            link.symlink_to(lab.ROOT / name, target_is_directory=True)
    log = ROOM/'launcher.log'
    with log.open('w') as out:
        proc = subprocess.Popen([sys.executable, '-u', __file__, '--serve', str(speed)],
                                stdin=subprocess.DEVNULL, stdout=out, stderr=out, start_new_session=True)
    print(f'Starting slow sumo at {speed:g}× ...', flush=True)
    deadline = time.monotonic() + 210
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise SystemExit('Training room exited. See '+str(log))
        if ready(proc.pid):
            print(f'Join {ADDRESS} with the practice mod, then use /duel sumo-league-remote-001 sumo preset=mmc cps=7')
            return
        time.sleep(.5)
    raise SystemExit('Training room is still starting. See '+str(log))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('speed', type=float, default=.5, nargs='?', help='Game speed from 0.1 to 1 (default 0.5)')
    parser.add_argument('--stop', action='store_true')
    parser.add_argument('--serve', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not .1 <= args.speed <= 1:
        parser.error('Choose a speed between 0.10 and 1.00')
    DATA.mkdir(parents=True, exist_ok=True)
    if args.serve:
        asyncio.run(lab.serve(bot_slots=2, port_offset=20, training_root=ROOM, training_speed=args.speed))
    else:
        with (ROOM/'launcher.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            change(args.speed, args.stop)
