"""Read one complete Forge POV, following its writer's segment chain."""
import gzip
import json
from pathlib import Path

def events(path, types=None):
    # Match compact recorder lines before decoding large unrelated telemetry.
    prefixes=tuple('{"type":'+json.dumps(t) for t in set(types)|{'capture_end'}) if types is not None else ()
    seen=set()
    while path:
        if path in seen:raise ValueError(f'Capture segment cycle at {path}')
        seen.add(path)
        footer=None
        with gzip.open(path,'rt') as stream:
            for line in stream:
                if prefixes and line.startswith('{"type":"') and not line.startswith(prefixes):
                    continue
                row=json.loads(line)
                if row['type']=='capture_end':footer=row
                if types is None or row['type'] in types:yield row
        if not footer or not footer['writer_complete'] or footer['dropped'] or footer['size_capped']:
            raise ValueError(f'Incomplete capture {path}')
        name=footer.get('continues')
        if name and Path(name).name!=name:raise ValueError(f'Invalid capture continuation in {path}')
        path=path.with_name(name) if name else None
