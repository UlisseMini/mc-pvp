import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Reconstruct the server sprint flag immediately before each accepted C02 in a model drain. */
final class SprintPacketLedger {
    final ArrayList<List<Object>> events=new ArrayList<>();
    private final ArrayList<PeriodicComboFightModel.Packet> pending=new ArrayList<>();
    private final boolean[] flags=new boolean[2];
    private final double start;
    private final boolean first;

    SprintPacketLedger(PeriodicComboFightModel.State state,double start,boolean first){
        this.start=start;this.first=first;remember(state);
    }

    private void remember(PeriodicComboFightModel.State state){
        flags[0]=state.serverSprint;flags[1]=state.serverSprintB;
        pending.clear();
        for(var p:state.packets)if(p.kind==2||p.kind==9||p.kind==3||p.kind==7)pending.add(p);
    }

    void observe(double time,String stage,PeriodicComboFightModel.State s,List<DuelEnv.Hit> hits,int before){
        if(stage.equals("SERVER_DRAIN")){
            var accepted=new HashMap<Long,DuelEnv.Hit>();
            for(int n=before;n<hits.size();n++){
                var h=hits.get(n);
                long packet=h.upgrade()?(h.sideA()?s.lastAcceptedPacketA:s.lastAcceptedPacketB)
                        :(h.sideA()?s.lastFreshPacketA:s.lastFreshPacketB);
                if(accepted.put(packet,h)!=null)throw new AssertionError("Duplicate accepted attack");
            }
            pending.sort(Comparator.comparingDouble((PeriodicComboFightModel.Packet p)->p.ready).thenComparingLong(p->p.sequence));
            for(var p:pending){
                if(p.ready>time)break;
                boolean a=p.kind==2||p.kind==3,ours=a==first;int index=a?0:1;
                if(p.kind==2||p.kind==9){
                    flags[index]=p.flag;
                    events.add(List.of(time-start,ours,"sprint",p.flag,p.sequence));
                }else{
                    var h=accepted.remove(p.sequence);
                    if(h==null)continue;
                    if(h.sideA()!=a||h.at()!=time)throw new AssertionError("Mismatched C02");
                    var impulse=a?s.lastFreshB:s.lastFreshA;
                    double speed=h.upgrade()?0:Math.hypot(impulse.x(),impulse.z());
                    if(!h.upgrade()&&!flags[index]&&Math.abs(speed-.5274)>.0002)
                        throw new AssertionError("Non-sprint impulse disagrees with reconstructed flag");
                    events.add(List.of(time-start,ours,h.upgrade()?"upgrade":"fresh",flags[index],p.sequence,speed));
                    flags[index]=false;
                }
            }
            if(!accepted.isEmpty()||flags[0]!=s.serverSprint||flags[1]!=s.serverSprintB)
                throw new AssertionError("Server sprint reconstruction disagrees with the actual drain");
        }
        remember(s);
    }
}
