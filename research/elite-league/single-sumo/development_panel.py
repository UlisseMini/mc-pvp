"""Common paired action seeds for balanced clock, seat and RTT comparisons."""
import random
from learner import RTTS


def make_panel(clocks,rep_count):
    cells=[]
    for i,clock in enumerate(clocks):
        for rep in range(rep_count):
            for side in 'ab':
                for a,b in RTTS:
                    idx=len(cells);rng=random.Random(5533711+idx*71023)
                    cells.append((i,rep,side,a,b,clock,rng.getrandbits(60),rng.getrandbits(60)))
    return cells
