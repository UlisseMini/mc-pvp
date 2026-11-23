"""Lossless bot event history. Standard library only; no automatic live rotation.

prepare_rotation may run while clients are idle and explicitly disabled. It reads
and compresses the source, verifies its decompressed SHA256, then checks the source
snapshot again. finalize_rotation is intentionally separate: the caller must stop
ALL writers to that source, verify no append is in flight, and keep them stopped
until it returns. writer_stopped=True is that explicit caller attestation, not a
claim inferred from an idle file or advisory lock. No process is stopped here.
"""
import gzip
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import time
import uuid


class EventLogError(RuntimeError):
    pass


def _snapshot(path):
    value = path.stat()
    if not stat.S_ISREG(value.st_mode):
        raise EventLogError('Event log/archive must be a regular file')
    return {key: getattr(value, 'st_' + key) for key in
            ('dev', 'ino', 'size', 'mtime_ns', 'ctime_ns', 'mode')}


def _locations(path):
    path = Path(path).absolute()
    if path.is_symlink():
        raise EventLogError('Refuse a symlink event log')
    return path, path.parent / 'events-history', path.parent / 'events-history/manifest.json'


def _manifest(path):
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        return {'version': 1, 'archives': []}, None
    value = json.loads(data)
    if value.get('version') != 1 or not isinstance(value.get('archives'), list):
        raise EventLogError('Unsupported event history manifest')
    for entry in value['archives']:
        name = entry['file']
        if Path(name).name != name or not name.endswith('.jsonl.gz'):
            raise EventLogError('Invalid archive name in manifest')
    return value, hashlib.sha256(data).hexdigest()


def _disabled(path, runtime_root):
    runtime = Path(runtime_root).absolute() if runtime_root is not None else path.parents[2]
    if path.parent.parent != runtime / 'bots' or path.name != 'events.jsonl':
        raise EventLogError('Rotation requires runtime/bots/{slot}/events.jsonl')
    try:
        status = json.loads((runtime / 'status.json').read_text())
        if not isinstance(status['matches'], list) or status['matches']:
            raise EventLogError('Refuse rotation while any match is active')
        controls = list((runtime / 'bots').glob('*/control.properties'))
        if path.parent / 'control.properties' not in controls:
            raise EventLogError('Source client controls are missing')
        for control in controls:
            values = dict(line.split('=', 1) for line in control.read_text().splitlines()
                          if '=' in line and not line.lstrip().startswith(('#', '!')))
            if values.get('enabled', '').strip().lower() != 'false':
                raise EventLogError('Every existing bot client must be explicitly disabled')
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise EventLogError('Cannot establish idle, disabled clients') from error
    return runtime


def _fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _atomic_json(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix='.manifest-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, separators=(',', ':'), sort_keys=True)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    finally:
        Path(temporary).unlink(missing_ok=True)


def _verify_archive(path):
    digest = hashlib.sha256()
    count = 0
    with gzip.open(path, 'rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            count += len(chunk)
    return digest.hexdigest(), count


def prepare_rotation(path, *, runtime_root=None):
    """Create a verified archive/plan; never replace the live log or manifest.

    Returned plan is JSON serializable. Keep the archive and plan together until
    finalize_rotation. If this fails, source and committed history are unchanged;
    any successfully created draft archive is deliberately retained, never deleted.
    """
    path, history, manifest_path = _locations(path)
    runtime = _disabled(path, runtime_root)
    before = _snapshot(path)
    if not before['size']:
        raise EventLogError('Live log is empty; nothing to rotate')
    manifest, revision = _manifest(manifest_path)
    prefix = manifest.get('live_prefix', {})
    if (prefix.get('dev'), prefix.get('ino')) == (before['dev'], before['ino']):
        raise EventLogError('A prior finalize was interrupted; finish that plan before preparing again')
    history.mkdir(exist_ok=True)
    archive = history / ('events-' + str(time.time_ns()) + '-' + uuid.uuid4().hex + '.jsonl.gz')
    digest = hashlib.sha256()
    identifiers = set()
    complete = True
    ended_newline = False
    count = 0
    with path.open('rb') as source, archive.open('xb') as target:
        if _snapshot(path) != before or os.fstat(source.fileno()).st_ino != before['ino']:
            raise EventLogError('Source changed before compression')
        with gzip.GzipFile(filename='', mode='wb', fileobj=target, compresslevel=1, mtime=0) as compressed:
            for line in source:
                digest.update(line)
                compressed.write(line)
                count += len(line)
                ended_newline = line.endswith(b'\n')
                try:
                    event = json.loads(line)
                    if not isinstance(event, dict):
                        complete = False
                    elif isinstance(event.get('match'), str):
                        identifiers.add(event['match'])
                    elif event.get('match') is not None:
                        complete = False
                except (ValueError, UnicodeError):
                    complete = False
        target.flush()
        os.fsync(target.fileno())
    expected = digest.hexdigest()
    archive_before = _snapshot(archive)
    actual, verified_bytes = _verify_archive(archive)
    if _snapshot(archive) != archive_before:
        raise EventLogError('Archive changed during verification')
    if actual != expected or verified_bytes != count or count != before['size']:
        raise EventLogError('Compressed history failed decompressed SHA256/size verification')
    if _snapshot(path) != before:
        raise EventLogError('Source changed while preparing history; live log retained')
    _disabled(path, runtime)
    if _manifest(manifest_path)[1] != revision:
        raise EventLogError('History manifest changed during preparation')
    entry = {'file': archive.name, 'sha256': expected, 'uncompressed_bytes': count,
             'match_ids': sorted(identifiers), 'match_index_complete': complete and ended_newline,
             'ends_newline': ended_newline}
    _fsync_directory(history)
    return {'version': 1, 'source': str(path), 'runtime_root': str(runtime),
            'source_stat': before, 'archive_stat': archive_before,
            'manifest_sha256': revision, 'entry': entry, 'verified_sha256': actual}


def _finalize_rotation(plan, *, writer_stopped=False):
    """Publish verified history and atomically replace live log with an empty file.

    The writer must remain stopped throughout. This does no large-file hashing:
    exact inode/size/mtime/ctime snapshots bind both source and verified archive to
    prepare_rotation. If interrupted after manifest publication, live_prefix makes
    readers skip the archived prefix in the old inode, avoiding duplicate records.
    Repeating this same plan while the original writer is still stopped is safe.
    """
    if writer_stopped is not True:
        raise EventLogError('Caller must stop the source writer and pass writer_stopped=True')
    if plan.get('version') != 1 or plan['verified_sha256'] != plan['entry']['sha256']:
        raise EventLogError('A verified prepare_rotation plan is required')
    path, history, manifest_path = _locations(plan['source'])
    runtime = _disabled(path, plan['runtime_root'])
    if _snapshot(path) != plan['source_stat']:
        raise EventLogError('Source identity/size/mtime changed after preparation')
    if _snapshot(history / plan['entry']['file']) != plan['archive_stat']:
        raise EventLogError('Verified archive changed after preparation')
    manifest, revision = _manifest(manifest_path)
    prefix = {key: plan['source_stat'][key] for key in ('dev', 'ino', 'size')}
    retry = (manifest.get('live_prefix') == prefix and manifest['archives']
             and manifest['archives'][-1] == plan['entry'])
    if not retry and revision != plan['manifest_sha256']:
        raise EventLogError('History manifest changed after preparation')
    descriptor, empty = tempfile.mkstemp(prefix='.events-empty-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            os.fchmod(stream.fileno(), stat.S_IMODE(plan['source_stat']['mode']))
            stream.flush()
            os.fsync(stream.fileno())
        _disabled(path, runtime)
        if _snapshot(path) != plan['source_stat']:
            raise EventLogError('Source changed immediately before publication')
        if not retry:
            manifest = {**manifest, 'archives': [*manifest['archives'], plan['entry']], 'live_prefix': prefix}
            _atomic_json(manifest_path, manifest)
        # No truncation: old readers retain their inode; stopped writers cannot
        # append through a pre-opened descriptor while this replacement happens.
        os.replace(empty, path)
        _fsync_directory(path.parent)
    finally:
        Path(empty).unlink(missing_ok=True)
    return plan['entry']


def finalize_rotation(plan, *, writer_stopped=False):
    """Finalize under a short utility lock; caller still MUST stop native writers.

    The advisory lock only serializes this helper against itself. It cannot stop
    Files.writeString(APPEND), so a disabled/quiet client alone is insufficient.
    """
    if writer_stopped is not True:
        raise EventLogError('Caller must stop the source writer and pass writer_stopped=True')
    _, history, _ = _locations(plan['source'])
    with (history / '.rotation.lock').open('a+b') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise EventLogError('Another history finalization is in progress') from error
        try:
            return _finalize_rotation(plan, writer_stopped=True)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


class _JoinedRaw(io.RawIOBase):
    """Stream archive files followed by an already-open live inode as one text file."""
    def __init__(self, archives, live):
        self.archives = iter(archives)
        self.live = live
        self.current = None
        self.at_live = False

    def readable(self):
        return True

    def readinto(self, buffer):
        while True:
            if self.current is None:
                try:
                    self.current = gzip.open(next(self.archives), 'rb')
                except StopIteration:
                    if self.at_live:
                        return 0
                    self.current = self.live
                    self.at_live = True
            count = self.current.readinto(buffer)
            if count:
                return count
            if self.current is not self.live:
                self.current.close()
            self.current = None

    def close(self):
        if self.current is not None and self.current is not self.live:
            self.current.close()
        self.live.close()
        super().close()


def iter_events(path, identifier=None):
    """Yield historical gzip then current JSON records in byte-stream chronology.

    Filtering preserves the existing needle-before-json.loads behavior: unrelated
    malformed lines are ignored, matching malformed lines still raise. An archive
    is skipped by match index only if every archived segment has a complete index;
    malformed/non-object/partial lines conservatively disable that optimization.
    Opening live BEFORE reading the atomic manifest avoids losing a rotation's
    bytes when a reader starts during finalize. UTF8/newline/partial-line behavior
    matches reading the concatenated original file through a normal text stream.
    """
    path, history, manifest_path = _locations(path)
    with path.open('rb') as live:
        identity = os.fstat(live.fileno())
        manifest, _ = _manifest(manifest_path)
        prefix = manifest.get('live_prefix', {})
        if (prefix.get('dev'), prefix.get('ino')) == (identity.st_dev, identity.st_ino):
            if identity.st_size < prefix['size']:
                raise EventLogError('Live source is shorter than its archived prefix')
            live.seek(prefix['size'])
        entries = manifest['archives']
        can_skip = identifier is not None and all(e.get('match_index_complete') for e in entries)
        archives = [history / e['file'] for e in entries
                    if not can_skip or identifier in e['match_ids']]
        needle = json.dumps(identifier) if identifier is not None else None
        with io.TextIOWrapper(io.BufferedReader(_JoinedRaw(archives, live)), encoding='utf-8') as stream:
            for line in stream:
                if needle is not None and needle not in line:
                    continue
                event = json.loads(line)
                if identifier is None or event.get('match') == identifier:
                    yield event
