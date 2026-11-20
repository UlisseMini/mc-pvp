"""Native transfer of the fixed generation-one best-response attempt, including plain W-tap."""
from native_league import *
from forge_window import window
folder=EVIDENCE/'g001'
def main():
    config=read(folder/'generation.json');model=pin_model(folder/'exploiter')
    name=publish('sg001-counter',model,'Generation 1 GRU best-response attempt, 7 CPS')
    panel=fixtures('sg001-smoke',[name,WT],[INCUMBENT],8,7)
    with window() as bridge:cohort(folder/'native-exploiter',panel,bridge)
if __name__=='__main__':main()
