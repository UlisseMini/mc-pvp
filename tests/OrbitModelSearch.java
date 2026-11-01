package lab;

import java.util.*;

/** Offline hypothesis screen only. Native tournaments must validate predictions. */
public final class OrbitModelSearch {
    static Map<String,Object> trial(String name,int enter,boolean enabled,boolean selective,boolean ctrl,int repair,double opening,double spacing,
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
        settings.enabled=enabled;settings.entryHits=enter;settings.maintenanceCtrl=ctrl;
        settings.repairEvery=repair;settings.openingRange=opening;settings.spacingRange=spacing;
        MotionClickController.Settings clicks=new MotionClickController.Settings(50,true,50);
        int beforeOur=0,beforeTheir=0,chain=0,longest=0,maintenanceTicks=0;
        long firstReturn=-1;List<Object> cycleSamples=new ArrayList<>(),openingSamples=new ArrayList<>();
        double lastViewX=state.aSeesB.x,lastViewZ=state.aSeesB.z,tvx=0,tvz=0;
        for(int t=0;t<seconds*20;t++){
            long outgoing=Math.max(0,state.lastPredictedHurtCueMs),incoming=Math.max(0,state.lastPredictedOwnHurtCueMs);
            double nearX=Math.max(0,Math.abs(state.aSeesB.x-state.a.x)-.4),nearZ=Math.max(0,Math.abs(state.aSeesB.z-state.a.z)-.4);
            double nearY=Math.max(0,Math.max(state.aSeesB.y-.1-state.a.y-1.62,state.a.y+1.62-state.aSeesB.y-1.9));
            double localRange=Math.sqrt(nearX*nearX+nearY*nearY+nearZ*nearZ);
            double dx=state.aSeesB.x-state.a.x,dz=state.aSeesB.z-state.a.z,gap=Math.hypot(dx,dz);
            double radial=(state.a.vx*dx+state.a.vz*dz)/Math.max(.001,gap);
            tvx=.5*tvx+.5*(state.aSeesB.x-lastViewX)/.05;tvz=.5*tvz+.5*(state.aSeesB.z-lastViewZ)/.05;
            lastViewX=state.aSeesB.x;lastViewZ=state.aSeesB.z;
            double targetAway=(tvx*dx+tvz*dz)/Math.max(.001,gap);
            OrbitController.Decision d=controller.decide(state.nowMs,outgoing,incoming,localRange,radial,targetAway,state.a.ground,state.a.sprint,settings);
            ComboModel.Action action=!d.forward?ComboModel.Action.RELEASE:d.sprintKey?ComboModel.Action.SPRINT:ComboModel.Action.WALK;
            boolean contact=ComboModel.rayDistance(state.a.x,state.a.y,state.a.z,state.aSeesB.x,state.aSeesB.y,state.aSeesB.z)<=3;
            boolean click=!selective||!d.maintenance||MotionClickController.decide(new MotionClickController.Input(
                state.nowMs,outgoing,ownPing,radial,state.a.sprint,true,contact),clicks).click();
            maintenanceTicks+=d.maintenance?1:0;
            ComboModel.State next=ComboModel.advanceRecorded(state,action,click,cfg);
            if(next.nowMs-start<=4000&&next.acceptedFreshHits>state.acceptedFreshHits){
                openingSamples.add(Compat.map("t",next.nowMs-start,"mode",d.maintenance?"normal":"acquire",
                    "fresh_sprint",next.lastPredictedHitSprint,"target_y",next.b.y,"own_y",next.a.y,
                    "own_ray",next.lastOurRay,"their_ray",next.lastDefenderRay,
                    "gap",Math.hypot(next.b.x-next.a.x,next.b.z-next.a.z),
                    "impulse",Compat.list(next.lastPredictedImpulseX,next.lastPredictedImpulseY,next.lastPredictedImpulseZ)));
            }
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
        result.put("maintenance_ticks",maintenanceTicks);result.put("cycle_samples",cycleSamples);result.put("opening_samples",openingSamples);return result;
    }
    public static void main(String[] args){
        int seconds=args.length>0?Integer.parseInt(args[0]):30;
        for(int[] ping:new int[][]{{20,20},{20,100},{100,20}})
            for(boolean wt:new boolean[]{false,true})
                for(int wake:wt?new int[]{0,1500,2000}:new int[]{0})
                    for(int variant=args.length>1?Integer.parseInt(args[1]):0;variant<13;variant++)
                        for(int[] phase:new int[][]{{25,10},{0,33},{40,17}}){
                            String name=new String[]{"acquire-only","normal-continuous","normal-selective","normal-selective-enter3","normal-continuous-enter1","normal-selective-enter1","ctrl-continuous-enter1","ctrl-selective-enter1","alternating","alternating-open36","alternating-open39","ctrl-spacing29","alternating-spacing29"}[variant];
                            int enter=variant>=4?1:variant==3?3:2;
                            boolean selective=variant==2||variant==3||variant==5||variant==7;
                            System.out.println(Json.encode(trial(name,enter,variant!=0,selective,variant>=6,variant>=8&&variant!=11?2:0,variant==9?3.6:variant==10?3.9:0,variant>=11?2.9:0,wt,wake,ping[0],ping[1],phase[0],phase[1],seconds)));
                        }
    }
}
