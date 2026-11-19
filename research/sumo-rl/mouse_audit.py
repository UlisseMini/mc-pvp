"""Reconstruct each applied virtual mouse count from the archived client observation."""
import math
import struct

def single(x):
    return struct.unpack('>f',struct.pack('>f',x))[0]

class MouseAudit:
    def __init__(self):
        self.rows=0
        self.plans=0
        self.nonzero=0
        self.resets=0
        self.last=None

    def record(self,e):
        if e['type']=='aim_plan':
            if e.get('input')!='mouse':raise ValueError('Absolute-angle tracker used during mouse cohort')
            self.plans+=1
        if e['type']!='mouse_input':return
        y,p=map(single,e['before']);ty,tp=map(single,e['target'])
        if e['sensitivity']!=.5 or e['inverted']:raise ValueError('Tournament mouse settings changed')
        s=single(single(single(e['sensitivity'])*single(.6))+single(.2))
        scale=single(single(single(s*s)*s)*8)
        dx=(ty-y+180)%360-180
        dp=max(-90,min(90,tp))-p
        mag=math.hypot(dx,dp);limit=.72*max(0,min(50,e['elapsed_ms']))
        frac=min(1,limit/mag) if mag else 1
        expected=[math.floor(dx*frac/(.15*scale)+.5),math.floor(-dp*frac/(.15*scale)+.5)]
        if list(e['counts'])!=expected:raise ValueError('Recorded mouse counts differ from planned local turn')
        ax=single(y+single(expected[0]*scale)*.15)
        ay=max(-90,min(90,single(p-single(expected[1]*scale)*.15)))
        after=list(map(single,e['after']))
        if after!=[ax,ay]:raise ValueError('Recorded vanilla yaw/pitch differ from integer input')
        if self.last is not None and self.last!=[y,p]:self.resets+=1
        self.rows+=1;self.nonzero+=int(any(expected));self.last=after

    def summary(self):
        if not self.rows or not self.plans:raise ValueError('No auditable virtual mouse stream')
        return dict(transitions=self.rows,nonzero=self.nonzero,aim_samples=self.plans,between_event_resets=self.resets)
