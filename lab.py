#!/usr/bin/env python3
"""Local Minecraft research lab. Python standard library only."""
from __future__ import annotations
import argparse
import concurrent.futures
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / '.cache'
BUILD = ROOT / 'build'
RUNTIME = ROOT / 'runtime'
MC_VERSION = '1.8.9'
HMC_COMMIT = 'c83e908335e0a23552a273143a219ec5600b9be9'
JAVA = next((path for path in (Path('/opt/homebrew/opt/openjdk@17/bin/java'),
           Path('/usr/local/opt/openjdk@17/bin/java'),Path(shutil.which('java') or '/usr/bin/java')) if path.exists())).resolve()
JAVAC = JAVA.with_name('javac')
RECORDS = {}
LOCK_PATH=ROOT/'dependencies.lock.json'
LOCK=json.loads(LOCK_PATH.read_text()) if LOCK_PATH.exists() else {}


def download(url, path, sha1=None, sha256=None):
    path = Path(path)
    known=LOCK.get(str(path.relative_to(ROOT)))
    if known:
        if known['url']!=url:raise RuntimeError(f'Locked source changed for {path}')
        if sha256 and sha256!=known['sha256']:raise RuntimeError(f'Upstream digest changed for {path}')
        sha256=known['sha256']
    path.parent.mkdir(parents=True, exist_ok=True)
    expected = sha256 or sha1
    algorithm = 'sha256' if sha256 else 'sha1'
    if path.exists():
        data = path.read_bytes()
        if expected and hashlib.new(algorithm, data).hexdigest() != expected:
            raise RuntimeError(f'Hash mismatch for cached {path}; refusing to replace silently')
    else:
        req = urllib.request.Request(url, headers={'User-Agent': 'Minecraft-PvP-Lab/0.1'})
        data = urllib.request.urlopen(req, timeout=60).read()
        if expected and hashlib.new(algorithm, data).hexdigest() != expected:
            raise RuntimeError(f'Hash mismatch for {url}')
        path.write_bytes(data)
    RECORDS[str(path.relative_to(ROOT))] = {'url': url, 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)}
    return data


def json_download(url, path):
    return json.loads(download(url, path))


def verify_cache():
    """Refuse to run cached dependencies whose bytes differ from the committed lock."""
    for relative,record in LOCK.items():
        path=ROOT/relative
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=record['sha256']:
            raise RuntimeError(f'Locked dependency missing or changed: {relative}; run prepare to inspect')


def prepare():
    """Fetch immutable source/data and verified Mojang artifacts; execute no downloads."""
    CACHE.mkdir(exist_ok=True)
    version_lock=ROOT/'minecraft-version.lock.json'
    if version_lock.exists():entry=json.loads(version_lock.read_text())
    else:
        manifest = json_download('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', CACHE / 'version_manifest.json')
        entry = next(v for v in manifest['versions'] if v['id'] == MC_VERSION)
    version = json.loads(download(entry['url'], CACHE / 'version.json', entry['sha1']))
    tasks = []
    for kind in ('client', 'server'):
        artifact = version['downloads'][kind]
        tasks.append((artifact['url'], CACHE / f'{kind}.jar', artifact['sha1']))
    libraries = []
    for lib in version['libraries']:
        artifact = lib.get('downloads', {}).get('artifact')
        if artifact:
            dest = CACHE / 'libraries' / artifact['path']
            libraries.append(str(dest))
            tasks.append((artifact['url'], dest, artifact['sha1']))
    logging = version['logging']['client']['file']
    tasks.append((logging['url'], CACHE / 'client-logging.xml', logging['sha1']))
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        list(pool.map(lambda t: download(*t), tasks))
    # Mojang's official server mitigation, linked from their security advisory.
    download('https://launcher.mojang.com/v1/objects/4bb89a97a66f350bc9f73b3ca8509632682aea2e/log4j2_17-111.xml', CACHE / 'server-logging.xml', '4bb89a97a66f350bc9f73b3ca8509632682aea2e')
    tree = json_download(f'https://api.github.com/repos/headlesshq/headlessmc/git/trees/{HMC_COMMIT}?recursive=1', CACHE / 'hmc-tree.json')['tree']
    prefix = 'headlessmc-lwjgl/src/main/java/'
    entries = [x for x in tree if x['path'].startswith(prefix) and '/launchwrapper/' not in x['path'] and x['path'].endswith('.java')]
    def source(item):
        relative = item['path'][len(prefix):]
        data = download(f'https://raw.githubusercontent.com/headlesshq/headlessmc/{HMC_COMMIT}/{item["path"]}', CACHE / 'hmc-original' / relative)
        git_hash = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if git_hash != item['sha']:
            raise RuntimeError(f'Git blob mismatch: {relative}')
        text = data.decode()
        constructor = '@RequiredArgsConstructor' in text
        utility = '@UtilityClass' in text
        text = re.sub(r'^import lombok\.[^;]+;\n', '', text, flags=re.M)
        text = re.sub(r'^@(Generated|RequiredArgsConstructor|UtilityClass)[^\n]*\n', '', text, flags=re.M)
        text = re.sub(r'\bval (\w+)\s*=', r'var \1 =', text)
        if utility:
            text = re.sub(r'^(    (?:public|private|protected) )(?!static\b)(?!(?:class|interface)\b)', r'\1static ', text, flags=re.M)
        if constructor:
            cls = re.search(r'public class (\w+)', text).group(1)
            fields = re.findall(r'^    private final ([^;=]+?) (\w+);', text, re.M)
            args = ', '.join(f'{typ} {name}' for typ, name in fields)
            assignments = ' '.join(f'this.{name} = {name};' for _, name in fields)
            text = text.replace('\n}', f'\n    public {cls}({args}) {{ {assignments} }}\n}}')
        dest = BUILD / 'hmc-source' / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text)
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        list(pool.map(source, entries))
    download(f'https://raw.githubusercontent.com/headlesshq/headlessmc/{HMC_COMMIT}/LICENSE', CACHE / 'HEADLESSMC-LICENSE')
    for name in ('asm', 'asm-tree'):
        base = f'https://repo.maven.apache.org/maven2/org/ow2/asm/{name}/9.9.1/{name}-9.9.1.jar'
        locked=LOCK.get(str((CACHE/f'{name}.jar').relative_to(ROOT)))
        digest = locked['sha256'] if locked else urllib.request.urlopen(base + '.sha256', timeout=30).read().decode().strip().split()[0]
        download(base, CACHE / f'{name}.jar', sha256=digest)
    mapping_url = 'https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.8.9/mcp-1.8.9-srg.zip'
    mappings = download(mapping_url, CACHE / 'mcp-srg.zip')
    with zipfile.ZipFile(io.BytesIO(mappings)) as archive:
        srg_name = next(n for n in archive.namelist() if n.endswith('joined.srg'))
        (CACHE / 'joined.srg').write_bytes(archive.read(srg_name))
    stable = download('https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/22-1.8.9/mcp_stable-22-1.8.9.zip', CACHE / 'mcp-names.zip')
    names = {}
    with zipfile.ZipFile(io.BytesIO(stable)) as archive:
        for filename in ('methods.csv', 'fields.csv'):
            names.update({r['searge']: r['name'] for r in csv.DictReader(io.StringIO(archive.read(filename).decode()))})
    props = []
    for line in (CACHE / 'joined.srg').read_text().splitlines():
        parts = line.split()
        if parts[0] == 'CL:':
            props.append(f'C.{parts[2]}={parts[1]}')
        elif parts[0] == 'FD:':
            obf_cls, obf_name = parts[1].rsplit('/', 1)
            srg_name = parts[2].rsplit('/', 1)[1]
            props.append(f'F.{obf_cls}.{obf_name}={names.get(srg_name, srg_name)}')
        elif parts[0] == 'MD:':
            obf_cls, obf_name = parts[1].rsplit('/', 1)
            srg_name = parts[3].rsplit('/', 1)[1]
            props.append(f'M.{obf_cls}.{obf_name}{parts[2]}={names.get(srg_name, srg_name)}')
    (CACHE / 'mappings.properties').write_text('\n'.join(props))
    # Original jars stay intact. The agent disables legacy JNDI lookup methods at load time.
    (CACHE / 'classpath.json').write_text(json.dumps(libraries, indent=2))
    (CACHE / 'dependencies.json').write_text(json.dumps(RECORDS, indent=2, sort_keys=True))
    print(f'Prepared official Minecraft {MC_VERSION}, {len(entries)} HeadlessMC source files, ASM, and data-only mappings.')


def build():
    if not (CACHE / 'classpath.json').exists():
        prepare()
    verify_cache()
    BUILD.mkdir(exist_ok=True)
    sources = list((BUILD / 'hmc-source').rglob('*.java')) + list((ROOT / 'src').rglob('*.java'))
    classes = BUILD / 'classes'
    if classes.exists():shutil.rmtree(classes)
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(str(CACHE / f'{name}.jar') for name in ('asm', 'asm-tree'))
    subprocess.run([str(JAVAC), '-proc:none', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, sources)], check=True)
    temporary=BUILD/'lab-agent.jar.tmp'
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: lab.Agent\nCan-Retransform-Classes: true\n\n')
        for path in classes.rglob('*.class'):
            jar.write(path, str(path.relative_to(classes)))
        for name in ('asm', 'asm-tree'):
            with zipfile.ZipFile(CACHE / f'{name}.jar') as dependency:
                for member in dependency.namelist():
                    if member.endswith('.class') and not member.endswith('module-info.class'):
                        jar.writestr(member, dependency.read(member))
        jar.writestr('HEADLESSMC-LICENSE', (CACHE / 'HEADLESSMC-LICENSE').read_bytes())
        jar.write(CACHE / 'joined.srg', 'lab/joined.srg')
        jar.write(CACHE / 'mappings.properties', 'lab/mappings.properties')
    temporary.replace(BUILD/'lab-agent.jar')
    print('Built build/lab-agent.jar without Gradle or annotation processors.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['prepare', 'build','sandbox-test','logging-test','serve','stop','status','match','verify-ux'])
    parser.add_argument('extra',nargs='*')
    args = parser.parse_args()
    if args.command in ('prepare','build'):globals()[args.command]()
    else:
        import lab_runtime
        lab_runtime.run(args.command,args.extra)


if __name__ == '__main__':
    try:main()
    except (RuntimeError,OSError) as error:
        print('PvP Lab: '+str(error),file=sys.stderr);sys.exit(1)
