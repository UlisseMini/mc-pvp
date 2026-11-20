"""Pack an observation-only GRU and three frozen MLPs into the tested 24-decision Forge actor format."""
import base64
import numpy as np
from learner import freeze


def export_router(sources,state,modes,folder):
    parts=[]
    for path in sources:
        family,encoded=path.read_bytes().strip().split(b'.')
        assert family==b'LEAGUE_MLP180'
        parts.append(base64.b64decode(encoded,validate=True))
    weights=lambda k:state[k].detach().numpy()
    readout=np.zeros((6,32),np.float32);readout[:2]=weights('readout.weight')
    bias=np.zeros(6,np.float32);bias[:2]=weights('readout.bias')
    classifier=[weights('encoder.weight_ih_l0'),weights('encoder.weight_hh_l0'),weights('encoder.bias_ih_l0'),
                readout,bias,weights('encoder.bias_hh_l0')]
    parts.extend(a.astype('>f4').tobytes() for a in classifier)
    models={}
    for role,mode in modes.items():
        data=b''.join(parts)+np.asarray([mode],dtype='>f4').tobytes()
        path=folder/(role+'.net');freeze(path,b'LEAGUE_ADAPT24.'+base64.b64encode(data)+b'\n');models[role]=path
    return models
