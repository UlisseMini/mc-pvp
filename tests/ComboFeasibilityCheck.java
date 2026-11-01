import java.util.*;

/** Branch variation cannot alter B before the earliest new A packet's B delivery. */
public final class ComboFeasibilityCheck {
    static PeriodicComboClock make(){var c=new PeriodicComboFightModel.Config();c.ownRtt=0;c.otherRtt=120;c.defenderReach=3;c.forceContacts=false;var t=new PeriodicComboClock.Timing();t.uplinkA=t.uplinkB=t.downlinkA=t.downlinkB=.4;return new PeriodicComboClock(new PeriodicComboFightModel.State(4093,0,4099,0),c,t,new ComboCycleSearch.Controller(new ComboCycleSearch.Plan(20,10,4,0,1,0),10),ComboOracleSearch.OPPONENT);}
    public static void main(String[] args){var base=make();base.runUntil(3949.999);var f=ComboFeasibilitySearch.committed(base,3);List<String> expected=null;int variants=0;
        for(int key=0;key<3;key++)for(int strafe=-1;strafe<=1;strafe++)for(boolean click:new boolean[]{false,true}){final int k=key,z=strafe;final boolean hit=click;var branch=base.fork(o->new PeriodicComboClock.Control(k,z*.98f,hit),ComboOracleSearch.OPPONENT);List<String> rows=new ArrayList<>();branch.setAudit((time,event,s)->rows.add(time+":"+event+":"+s.b.q+":"+s.ba.x+":"+s.ba.y+":"+s.ba.z+":"+s.ba.gx+":"+s.ba.gy+":"+s.ba.gz+":"+s.ba.left));branch.runUntil(f.cutoff()-1e-5);if(expected==null)expected=rows;else if(!expected.equals(rows))throw new AssertionError("B changed in committed prefix for "+key+","+strafe+","+click);variants++;}
        System.out.println("ComboFeasibilityCheck passed: "+variants+" key/strafe/click branches have identical B body/view through "+f.cutoff()+"ms exclusive");
    }
}
