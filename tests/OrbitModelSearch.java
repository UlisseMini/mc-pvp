package lab;

import java.util.*;

/** Offline hypothesis screen only. Native tournaments must validate predictions. */
public final class OrbitModelSearch {
    static Map<String,Object> trial(String name,int enter,boolean enabled,boolean selective,
            boolean wtap,int wake,int ownPing,int theirPing,int serverPhase,int targetPhase,int seconds){
        long start=1000,awaken=start+Math.max(150,wake);
        ComboModel.Config cfg=new ComboModel.Config();
        cfg.ownRttMs=ownPing;cfg.opponentRttMs=theirPing;
        cfg.serverPhaseMs=serverPhase;cfg.opponentPhaseMs=targetPhase;
        cfg.clickIntervalMs=cfg.defenderClickIntervalMs=50;
        cfg.defenderClicks=true;cfg.defenderWtap=wtap;
        cfg.defenderControlAtMs=start+150;cfg.defenderFirstClickAtMs=awaken;
        ComboModel.State state=new ComboModel.State(start,
            new ComboModel.Body(-3,0,0,0,-.0784,0,true,false),new ComboModel.Body(3,0,0,0,-.0784,0,true,false),
            new ComboModel.View(3,0,0),new ComboModel.View(-3,0,0),new ComboModel.View(-3,0,0),new ComboModel.View(3,0,0));
        OrbitController controller=new OrbitController();OrbitController.Settings settings=new OrbitController.Settings();
        settings.enabled=enabled;settings.entryHits=enter;
        MotionClickController.Settings clicks=new MotionClickController.Settings(50,true,50);
        int beforeOur=0,beforeTheir=0,chain=0,longest=0,maintenanceTicks=0;
        long firstReturn=-1;List<Object> cycleSamples=new ArrayList<>();
        for(int t=0;t<seconds*20;t++){
            long outgoing=Math.max(0,state.lastPredictedHurtCueMs),incoming=Math.max(0,state.lastPredictedOwnHurtCueMs);
            OrbitController.Decision d=controller.decide(state.nowMs,outgoing,incoming,settings);
            ComboModel.Action action=!d.forward?ComboModel.Action.RELEASE:d.sprintKey?ComboModel.Action.SPRINT:ComboModel.Action.WALK;
            double dx=state.aSeesB.x-state.a.x,dz=state.aSeesB.z-state.a.z,gap=Math.hypot(dx,dz);
            double radial=(state.a.vx*dx+state.a.vz*dz)/Math.max(.001,gap);
            boolean contact=ComboModel.rayDistance(state.a.x,state.a.y,state.a.z,state.aSeesB.x,state.aSeesB.y,state.aSeesB.z)<=3;
            boolean click=!selective||!d.maintenance||MotionClickController.decide(new MotionClickController.Input(
                state.nowMs,outgoing,ownPing,radial,state.a.sprint,true,contact),clicks).click();
            maintenanceTicks+=d.maintenance?1:0;
            ComboModel.State next=ComboModel.advanceRecorded(state,action,click,cfg);
            if(next.nowMs<awaken){beforeOur=next.acceptedFreshHits;beforeTheir=next.receivedFreshHits;}
            if(next.nowMs>=awaken){
                if(next.receivedHits>state.receivedHits){chain=0;if(firstReturn<0)firstReturn=next.lastPredictedReceivedHitMs-awaken;}
                if(next.acceptedFreshHits>state.acceptedFreshHits){
                    chain++;longest=Math.max(longest,chain);
                    cycleSamples.add(Compat.map("t",next.nowMs-start,"mode",d.maintenance?"normal":"acquire",
                        "fresh_sprint",next.lastPredictedHitSprint,"target_y",next.b.y,
                        "own_ray",next.lastOurRay,"their_ray",next.lastDefenderRay));
                }
            }
            state=next;
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("candidate",name);result.put("opponent",wtap?"wtap":"hold-w");
        result.put("wake_ms",wake);result.put("rtt",Compat.list(ownPing,theirPing));result.put("phase",Compat.list(serverPhase,targetPhase));
        result.put("seconds",seconds);result.put("accepted",Compat.list(state.acceptedHits,state.receivedHits));
        result.put("post_wake_fresh",Compat.list(state.acceptedFreshHits-beforeOur,state.receivedFreshHits-beforeTheir));
        result.put("longest_post_wake_run",longest);result.put("first_post_wake_return_ms",firstReturn<0?null:firstReturn);
        result.put("maintenance_ticks",maintenanceTicks);result.put("cycle_samples",cycleSamples);return result;
    }
    public static void main(String[] args){
        int seconds=args.length>0?Integer.parseInt(args[0]):30;
        for(int[] ping:new int[][]{{20,20},{20,100},{100,20}})
            for(boolean wt:new boolean[]{false,true})
                for(int wake:wt?new int[]{0,1500,2000}:new int[]{0})
                    for(int variant=0;variant<4;variant++)
                        for(int[] phase:new int[][]{{25,10},{0,33},{40,17}}){
                            String name=new String[]{"acquire-only","normal-continuous","normal-selective","normal-selective-enter3"}[variant];
                            System.out.println(Json.encode(trial(name,variant==3?3:2,variant!=0,variant>=2,wt,wake,ping[0],ping[1],phase[0],phase[1],seconds)));
                        }
    }
}
