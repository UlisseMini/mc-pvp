import lab.ComboModel;
import java.io.*;
import java.util.*;

/** Offline composition test: replay known A inputs, never correct predicted world state. */
public final class NativeRollout {
    static double d(String[] f,int i){return Double.parseDouble(f[i]);}
    public static void main(String[] args)throws Exception {
        BufferedReader r=new BufferedReader(new InputStreamReader(System.in));String[] h=r.readLine().split("\t");
        ComboModel.Config c=new ComboModel.Config();c.ownRttMs=d(h,0);c.opponentRttMs=d(h,1);
        c.serverPhaseMs=(int)d(h,2);c.opponentPhaseMs=(int)d(h,3);c.trackerPhase=(int)d(h,4);
        c.defenderMoves=d(h,5)!=0;c.defenderClicks=d(h,6)!=0;c.defenderControlAtMs=(long)d(h,7);
        c.defenderFirstClickAtMs=(long)d(h,8);c.defenderClickIntervalMs=105;c.clickIntervalMs=105;
        ComboModel.Body a=new ComboModel.Body(d(h,9),0,d(h,10),0,-.0784000015258789,0,true,false);
        ComboModel.Body b=new ComboModel.Body(d(h,11),0,d(h,12),0,-.0784000015258789,0,true,false);
        ComboModel.View va=new ComboModel.View(a.x,a.y,a.z),vb=new ComboModel.View(b.x,b.y,b.z);
        ComboModel.State state=new ComboModel.State(0,a,b,vb,va,va,vb);String line;
        while((line=r.readLine())!=null){
            String[] f=line.split("\t");ComboModel.Action action=ComboModel.Action.valueOf(f[0]);
            state=ComboModel.advanceRecorded(state,action,Boolean.parseBoolean(f[1]),c);
            StringJoiner out=new StringJoiner("\t");
            for(double v:new double[]{state.nowMs,state.a.x,state.a.y,state.a.z,state.b.x,state.b.y,state.b.z,
                    state.aSeesB.x,state.aSeesB.y,state.aSeesB.z,state.bSeesA.x,state.bSeesA.y,state.bSeesA.z,
                    state.acceptedFreshHits,state.receivedFreshHits,state.acceptedDamageUpgrades,state.receivedDamageUpgrades})out.add(Double.toString(v));
            System.out.println(out);
        }
    }
}
