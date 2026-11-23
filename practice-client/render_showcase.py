#!/usr/bin/env python3
"""Rebuild the GitHub Pages Bow Aim and two-POV Sumo media from local captures."""
import argparse
from dataclasses import dataclass
import gzip
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from build import BUILD, ROOT, SRC, compile_to, jar
from replay_smoke import forge_runtime, graphical_classpath, graphical_jvm_args

BOW_ARCHIVE = 'r89135e805d114bb8b48081428b99b615'
CLIPS = {
    'bow-aim-live': ('bowaim', 'r7e2b22b38b064c129c6a86b0cb5445b2', 22.826, 20, 145, 50, 800, 14, 112),
    'bow-aim-void-second': ('bowaim', 'r8898fef4cb2347c7a72da53cebaf8d5d', 28.565, 20, 145, 50, 800, 14, 112),
    'bow-aim-wait-still': ('bowaim', BOW_ARCHIVE, 115.75, 83, 6, 50, 0, 0, 0),
    'sumo-two-pov-live': ('sumo', 'm10373', 7.5, 5, 90, 50, 800, 14, 112),
    'bow-aim-placements': ('bowaim', BOW_ARCHIVE, 115.75, 20, 135, 50, 800, 14, 112),
}


@dataclass(frozen=True)
class VoidShot:
    parts: tuple[int, int]
    target: int
    message: str
    poster_frame: int


VOID_SHOTS = {
    'bow-aim-live': VoidShot((48, 63), 61, 'Calizum was thrown into the void by Nomophobia.', 31),
    'bow-aim-void-second': VoidShot((284, 302), 67, 'llucasl4 was thrown into the void by Nomophobia.', 28),
}


def stage_linked_session(first, staged):
    with gzip.open(first, 'rb') as source:
        header = json.loads(source.readline())
        session = Path(header['metadata']['full_session_capture'])
        if not session.is_absolute():
            session = first.parent / session
        session_dir = staged.parent / 'session'
        session_dir.mkdir()
        shutil.copyfile(session, session_dir / session.name)
        prefix = session.name.removesuffix('.jsonl.gz')
        for part in session.parent.glob(f'{prefix}-part*.jsonl.gz'):
            shutil.copyfile(part, session_dir / part.name)
        header['metadata']['full_session_capture'] = str(session_dir / session.name)
        with gzip.open(staged / first.name, 'wb') as dest:
            dest.write((json.dumps(header) + '\n').encode())
            shutil.copyfileobj(source, dest)


def capture_part(path):
    match = re.search(r'-part(\d+)\.', path.name)
    return int(match[1]) if match else 0


def stage_archive(game, archive_id, part_window=None):
    archive = ROOT / 'runtime/pov' / archive_id
    staged = game / 'archive' / archive_id
    staged.mkdir(parents=True)
    if part_window:
        parts = {capture_part(p): p for p in archive.glob('a-client-*-events*.jsonl.gz')}
        first, last = part_window
        # Keep JOIN_GAME and the subsequent packet chain without loading
        # earlier matches from the same connection.
        for number in range(first, last + 1):
            part = parts[number]
            (staged / (parts[0].name if number == first else part.name)).symlink_to(part)
        return staged.parent
    entries = list(archive.iterdir())
    if archive_id == BOW_ARCHIVE:
        # The clip ends before part 67. Load the prefix to fit the replay's memory budget.
        entries = [p for p in entries if capture_part(p) <= 66]
    if archive_id != BOW_ARCHIVE:
        first = next(archive.glob('a-client-*-events.jsonl.gz'))
        stage_linked_session(first, staged)
        entries.remove(first)
    for entry in entries:
        (staged / entry.name).symlink_to(entry)
    return staged.parent


def render(name, work, probe, classpath, output):
    mode, archive, start, warmup, frames, step, width, fps, colors = CLIPS[name]
    void_shot = VOID_SHOTS.get(name)
    game = work / name
    for directory in ('mods', 'config/pvp-practice', 'tmp', 'home'):
        (game / directory).mkdir(parents=True)
    staged = stage_archive(game, archive, void_shot.parts if void_shot else None)
    config = game / 'config/pvp-practice'
    shutil.copyfile(probe, game / 'mods/showcase-probe.jar')
    shutil.copyfile(forge_runtime.BRIDGE, game / 'mods/practice-bridge.jar')
    shutil.copyfile(forge_runtime.MODULE, config / 'practice-module.jar')
    settings = (SRC / 'settings.properties').read_text()
    settings += f'\narchive.directory={staged}\n'
    if mode == 'sumo':
        settings += ('reach.enabled=false\nghost.me.enabled=true\nghost.bot.enabled=true\n'
                     'advantage.enabled=true\nopening.enabled=true\nkeystrokes.enabled=true\n')
        (config / 'replay-display.properties').write_text(
            'recorded.reach_fields=false\nrecorded.own_velocity=false\nrecorded.change_log=false\n')
    else:
        settings += 'reach.enabled=false\nkeystrokes.enabled=false\nadvantage.enabled=false\nopening.enabled=false\n'
    (config / 'settings.properties').write_text(settings)
    (game / 'config/splash.properties').write_text('enabled=false\n')
    (game / 'options.txt').write_text(
        'renderDistance:6\nuseVbo:true\nfboEnable:true\nmaxFps:60\n'
        'soundCategory_master:0.0\nguiScale:3\n'
        + ('fov:-0.25\nchatVisibility:0\n' if void_shot else 'fov:0.25\nchatVisibility:2\n'))

    jvm_args = graphical_jvm_args(game, game)
    if void_shot:
        jvm_args += [f'-Dshowcase.voidTarget={void_shot.target}', f'-Dshowcase.voidMessage={void_shot.message}']
    args = jvm_args + [
        '-Xmx4G', '-XX:ParallelGCThreads=2', '-XX:ConcGCThreads=1',
        f'-Dshowcase.mode={mode}', f'-Dshowcase.id={archive}', f'-Dshowcase.archiveRoot={staged}',
        f'-Dshowcase.outDir={game}', f'-Dshowcase.startSec={start}', f'-Dshowcase.warmupTicks={warmup}',
        f'-Dshowcase.frames={frames}', f'-Dshowcase.stepMs={step}',
        f'-Dshowcase.checkPlacements={str(name == "bow-aim-placements").lower()}',
        f'-Dlog4j.configurationFile={ROOT / ".cache/server-logging.xml"}',
        '-cp', os.pathsep.join(map(str, classpath)), 'net.minecraft.launchwrapper.Launch',
        '--username', 'ShowcaseRender', '--version', '1.8.9', '--gameDir', str(game),
        '--assetsDir', str(forge_runtime.ASSETS), '--assetIndex', '1.8',
        '--uuid', '00000000-0000-0000-0000-000000000009', '--accessToken', '0',
        '--userProperties', '{}', '--userType', 'legacy',
        '--tweakClass', 'net.minecraftforge.fml.common.launcher.FMLTweaker',
        '--width', '1440', '--height', '810',
    ]
    log = game / 'console.log'
    with log.open('w') as console:
        result = subprocess.run(
            forge_runtime.command(args, game), cwd=game, stdout=console, stderr=subprocess.STDOUT,
            env={'PATH': '/usr/bin:/bin', 'LANG': 'en_US.UTF-8', 'TMPDIR': str(game / 'tmp') + '/'},
            timeout=180)
    screenshots = game / 'screenshots'
    messages = log.read_text(errors='replace')
    if result.returncode or '[ShowcaseProbe] CAPTURE COMPLETE' not in messages:
        raise RuntimeError(f'{name} capture failed in {log}\n{messages[-4500:]}')
    if len(list(screenshots.glob('frame_*.png'))) != frames:
        raise RuntimeError(f'{name} did not save all {frames} frames')

    if not width:
        shutil.copyfile(screenshots / 'frame_005.png', output / f'{name}.png')
        return

    sequence = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-framerate', '20',
                '-i', str(screenshots / 'frame_%03d.png')]
    subprocess.run(sequence + ['-c:v', 'libx264', '-preset', 'slow', '-crf', '16',
                   '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(output / f'{name}.mp4')], check=True)
    gif_filter = (f'fps={fps},scale={width}:-1:flags=lanczos,split[frames][colors];'
                  f'[colors]palettegen=max_colors={colors}:stats_mode=diff[palette];'
                  '[frames][palette]paletteuse=dither=bayer:bayer_scale=4')
    subprocess.run(sequence + ['-vf', gif_filter, str(output / f'{name}.gif')], check=True)
    poster_frame = void_shot.poster_frame if void_shot else (23 if mode == 'bowaim' else 30)
    poster = screenshots / f'frame_{poster_frame:03d}.png'
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(poster),
                    '-c:v', 'libwebp', '-quality', '90', str(output / f'{name}-poster.webp')], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clip', action='append', choices=CLIPS, help='Repeat to select multiple clips.')
    parser.add_argument('--output', default=str(BUILD / 'showcase-media'), help='Directory for the finished media.')
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='showcase-', dir=ROOT / 'runtime'))
    classpath = graphical_classpath(forge_runtime.stage_dependencies())
    sources = [SRC / 'smoke' / name for name in ('ShowcaseRenderProbe.java', 'ReplayGraphics.java')]
    libraries = json.loads((ROOT / '.cache/classpath.json').read_text())
    cp = os.pathsep.join([str(BUILD / 'compile-api.jar'), str(BUILD / 'api'), str(BUILD / 'core'), *libraries])
    compile_to(work / 'probe', sources, cp)
    probe = work / 'showcase-probe.jar'
    jar(probe, work / 'probe')
    for name in args.clip or (clip for clip in CLIPS if clip != 'bow-aim-placements'):
        print(f'Rendering {name} in {work}', flush=True)
        render(name, work, probe, classpath, output)
    print(f'Finished media in {output}')


if __name__ == '__main__':
    main()
