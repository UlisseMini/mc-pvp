"""Keep completed native ledger replays byte-identical and directly readable on APFS."""
import json
from pathlib import Path
import sys
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE.parent/'sumo-confidence'))
import pack_replays
def compress(folder):
    verified=folder/'verified.json'
    if not verified.exists():return
    midlist={r['mid'] for r in json.loads(verified.read_text())['matches'].values()}
    for mid in sorted(midlist):
        p=HERE.parents[2]/'runtime/replays'/(mid+'.jsonl')
        pack_replays.pack(p)
if __name__=='__main__':
    for root in sys.argv[1:]:compress(Path(root))
