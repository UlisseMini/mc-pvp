"""Encode the recovered block-state arrays for both the native lab and the simulator."""
import gzip
import hashlib
import json
import struct
from platforms import OUT,record

def pack():
    entries=json.loads((OUT/'catalog.json').read_text())
    for entry in entries:
        data=json.loads(gzip.decompress((OUT/(entry['name']+'.json.gz')).read_bytes()))
        v=data['volume']
        values=[s for layer in v['states'] for row in layer for s in row]
        states=struct.pack('>'+str(len(values))+'H',*values)
        hdr=struct.pack('>12i',entry['area'],*v['offset'],v['levels'][0],v['width'],v['depth'],len(v['levels']),*v['spawns'][0],*v['spawns'][1],int(entry['gap']))
        payload=b'MMC1'+hdr+bytes.fromhex(entry['exemplar'])+hashlib.sha256(states).digest()+states
        record(OUT/'packed'/(entry['name']+'.bin.gz'),gzip.compress(payload,mtime=0))
if __name__=='__main__':pack()
