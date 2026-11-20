import sys
from promotion import *
from assay import development
if __name__=='__main__':
    task=sys.argv[1];g=int(sys.argv[2]);folder=EVIDENCE/f'g{g:03d}'
    if task=='reversal':development(g,folder,read(folder/'assay.json'))
    elif task=='plateau':
        from plateau import search
        search(g)
    else:
        clocks=read(folder/'calendars/manifest.json')['selected']
        if task=='model':model_panel(g,read(folder/'nomination.json'),clocks)
        elif task=='transfer':model20(g,read(folder/'nomination.json'),clocks)
        else:raise ValueError(task)
