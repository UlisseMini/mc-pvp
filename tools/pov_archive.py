#!/usr/bin/env python3
"""Inspect the shared three-view archive, or stream one source's raw records as JSONL."""
import argparse
import collections
import gzip
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent / 'runtime/pov'

def records(path):
    with gzip.open(path, 'rt') as stream:
        for line in stream:
            yield json.loads(line)

def inspect(match, full=False):
    folder = ROOT / match
    report = {'match': match, 'manifest': json.loads((folder/'manifest.json').read_text()), 'files': []}
    for path in sorted(folder.glob('*.jsonl.gz')):
        if not full and '-events' in path.name:
            continue
        row = {'file': path.name, 'compressed_bytes': path.stat().st_size}
        kinds = collections.Counter(); times=[]; footer=None
        try:
            for record in records(path):
                kinds[record.get('type', '?')] += 1
                if record.get('type') == 'pov_frame':times.append(record['time_ms'])
                if record.get('type') == 'capture_end':footer=record
        except (EOFError, OSError, ValueError) as error:
            row['error'] = str(error)
        continuation = footer.get('continues') if footer else None
        next_exists = not continuation or (folder/Path(continuation).name).is_file()
        row.update(counts=dict(kinds), integrity=footer, continuation_present=bool(next_exists), complete=bool(footer and footer.get('writer_complete') and next_exists and not kinds['observer_error'] and not kinds['packet_omitted']))
        if times:
            times.sort(); gaps=sorted(b-a for a,b in zip(times,times[1:]));span=times[-1]-times[0]
            row.update(first_ms=times[0], last_ms=times[-1], samples=len(times), hz=(len(times)-1)*1000/span if span else None, max_gap_ms=max(gaps,default=0), median_gap_ms=gaps[len(gaps)//2] if gaps else None)
        report['files'].append(row)
    report['missing_sources'] = [source for source in report['manifest'].get('expected_sources',('a-client', 'b-client', 'server')) if not any(folder.glob(source+'-*-events*.jsonl.gz'))]
    if report['manifest'].get('role')=='human-remote':
        report['unavailable_sources']=[s for s in ('a-client','b-client','server')if s not in report['manifest']['available_sources']]
        report['recording_scope']=report['manifest']['source_scope']
    report['full_integrity_checked'] = full
    report['all_streams_complete'] = bool(full and not report['missing_sources'] and report['files'] and all(row['complete'] for row in report['files']))
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('match');parser.add_argument('--full',action='store_true',help='Also scan full packet/event archives for integrity and event counts')
    parser.add_argument('--export',choices=['a','b','server'],help='Stream raw records to stdout for analysis; redirect into your own file')
    parser.add_argument('--events',action='store_true',help='Export full events instead of the pose index');args=parser.parse_args()
    if not re.fullmatch(r'(?:m[0-9]{4,9}|r[0-9a-f]{32})',args.match):parser.error('Expected a local match ID (m1020) or remote session ID (r + 32 hex digits)')
    if args.export:
        source=args.export+'-client' if args.export!='server' else 'server';kind='events' if args.events or source=='server' else 'poses'
        for path in sorted((ROOT/args.match).glob(source+'-*-'+kind+'*.jsonl.gz')):
            for row in records(path):print(json.dumps(row,separators=(',',':')))
    else:print(json.dumps(inspect(args.match,args.full),indent=2))

if __name__=='__main__':main()
