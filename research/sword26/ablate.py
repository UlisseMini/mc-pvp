"""Freeze counterfactual controls for analysis of a trained tree policy."""
from pathlib import Path
import struct
import sys


def ablate(path,folder):
    folder.mkdir(parents=True,exist_ok=True)
    raw=path.read_bytes()
    if struct.unpack(">II",raw[:8])!=(0x53575431,132):
        raise ValueError("This audit requires a trained six-head decision tree")
    original=struct.unpack(">132d",raw[8:])
    for label,head,value in [("jump-released",3,-1),("jump-held",3,1),("sprint-held",2,1),
                             ("forward-held",0,1),("side-neutral",1,0)]:
        w=list(original);base=22*head+14
        w[base:base+8]=[value]*8
        dest=folder/(label+".net")
        dest.write_bytes(raw[:8]+struct.pack(">132d",*w))


if __name__=="__main__":ablate(Path(sys.argv[1]),Path(sys.argv[2]))
