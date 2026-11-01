package lab;

import java.util.*;

/** Delayed-observation adapter for the provisional no-click-opponent model. */
public final class LegacyModelController {
    static final TreeMap<Long,ComboModel.State> snapshots=new TreeMap<>();
    static final TreeMap<Long,Issued> inputs=new TreeMap<>();
    static ComboModel.Config config;
    static double originX,originZ;
    static long lastInjection,lastIssued;
    static final class Issued {
        final ComboModel.Action action;final boolean click;
        Issued(ComboModel.Action action,boolean click){this.action=action;this.click=click;}
    }
    static void reset(){snapshots.clear();inputs.clear();config=null;lastInjection=lastIssued=0;}
    static void click(long time){lastInjection=time;}
    static void idle(long time){inputs.put(time,new Issued(ComboModel.Action.RELEASE,false));lastIssued=time;inputs.headMap(time-3000,false).clear();}
    static ComboModel.Action decide(Client.Observation obs,long now){
        if(config==null){
            originX=obs.x;originZ=obs.z;
            config=new ComboModel.Config();
            config.defenderControlAtMs=Client.epoch+150;
        }
        config.ownRttMs=Client.number("ping",0);config.opponentRttMs=Client.number("opponent_ping",0);
        config.serverPhaseMs=(int)Client.number("server_phase_ms",25);config.opponentPhaseMs=(int)Client.number("opponent_phase_ms",10);
        config.trackerPhase=(int)Client.number("tracker_phase",0);
        config.horizonTicks=(int)Client.number("model_horizon_ticks",26);config.beamWidth=(int)Client.number("model_beam_width",24);
        config.minimumActionTicks=(int)Client.number("model_min_action_ticks",2);
        config.opportunityPenalty=Client.number("opportunity_penalty",300);config.penetrationPenalty=Client.number("penetration_penalty",300);
        config.hitReward=Client.number("hit_reward",1000);config.clickIntervalMs=(int)Client.number("model_click_ms",105);
        config.normalImpulseX=Client.number("normal_impulse_x",.4);config.normalImpulseY=Client.number("normal_impulse_y",.3608);
        config.sprintImpulseX=Client.number("sprint_impulse_x",.9);config.sprintImpulseY=Client.number("sprint_impulse_y",.4608);
        config.dynamicServerImpulses=Client.number("dynamic_server_impulses",1)>0;
        double x=obs.x-originX,z=obs.z-originZ,tx=obs.tx-originX,tz=obs.tz-originZ,y=obs.y-4,ty=obs.ty-4;
        Map.Entry<Long,ComboModel.State> anchor=snapshots.floorEntry(obs.time);
        ComboModel.State state;
        double ownError=0,viewError=0;
        if(anchor==null){
            ComboModel.Body a=new ComboModel.Body(x,y,z,obs.vx,obs.vy,obs.vz,obs.ground,obs.sprint);
            // At initial setup both players are stationary. The defender's later
            // state is inferred by the model, never read from its private client.
            ComboModel.Body b=new ComboModel.Body(tx,ty,tz,0,-.0784,0,ty<=.01,false);
            state=new ComboModel.State(obs.time,a,b,new ComboModel.View(tx,ty,tz),new ComboModel.View(x,y,z),new ComboModel.View(x,y,z),new ComboModel.View(tx,ty,tz));
        }else{
            state=anchor.getValue().copy();
            ownError=Math.sqrt(Math.pow(state.a.x-x,2)+Math.pow(state.a.y-y,2)+Math.pow(state.a.z-z,2));
            double ex=tx-state.aSeesB.x,ey=ty-state.aSeesB.y,ez=tz-state.aSeesB.z;
            viewError=Math.sqrt(ex*ex+ey*ey+ez*ez);
            state.correctOwn(x,y,z,obs.vx,obs.vy,obs.vz,obs.ground,obs.sprint);
            state.correctObservedDefender(tx,ty,tz);
            double gain=Client.number("velocity_feedback",0);
            state.b.vx=Math.max(-1.3,Math.min(1.3,state.b.vx+gain*ex));
            state.b.vz=Math.max(-1.3,Math.min(1.3,state.b.vz+gain*ez));
            state.b.vy=Math.max(-1.5,Math.min(.6,state.b.vy+gain*ey));
            state.nowMs=obs.time;
        }
        state.aLastSentSprint=obs.sentSprint; // our own delayed outgoing flag, not server sprint
        ComboModel.CueCorrection cue=null;
        if(Client.lastObservedTargetHurt>0)cue=state.assimilateFreshHurt(Client.lastObservedTargetHurt,config);
        snapshots.tailMap(obs.time,true).clear();snapshots.put(state.nowMs,state.copy());
        for(Map.Entry<Long,Issued> issued:new ArrayList<>(inputs.subMap(obs.time,true,now,false).entrySet())){
            // Replay actual native tick boundaries, including initial idle ticks
            // and catch-up ticks; do not invent extra/missing actions by rounding.
            state.nowMs=issued.getKey();snapshots.put(state.nowMs,state.copy());
            state=ComboModel.advanceRecorded(state,issued.getValue().action,issued.getValue().click,config);
        }
        state.nowMs=now;
        state.alignPhysicalClickClock(lastInjection,config);
        if(lastInjection>lastIssued)state.nextPhysicalClickMs=now; // our own queued input, not external feedback
        ComboModel.Decision decision=ComboModel.choose(state,config);
        snapshots.put(now,state.copy());
        Map<String,Object> entry=new LinkedHashMap<>();
        entry.put("type","mpc_plan");entry.put("time",now);entry.put("observation_time",obs.time);entry.put("match",Client.match);
        entry.put("action",decision.action.name());entry.put("predicted_hits",decision.predictedHits);entry.put("predicted_defender_opportunity_ticks",decision.predictedDefenderOpportunityTicks);
        entry.put("score",decision.score);entry.put("own_observation_error",ownError);entry.put("view_observation_error",viewError);
        entry.put("predicted_own_x",state.a.x);entry.put("predicted_target_x",state.b.x);entry.put("predicted_target_view_x",state.aSeesB.x);
        entry.put("predicted_own_y",state.a.y);entry.put("predicted_own_z",state.a.z);entry.put("predicted_target_y",state.b.y);entry.put("predicted_target_z",state.b.z);
        entry.put("predicted_target_vx",state.b.vx);entry.put("predicted_target_vy",state.b.vy);entry.put("predicted_target_vz",state.b.vz);
        entry.put("predicted_target_view_y",state.aSeesB.y);entry.put("predicted_target_view_z",state.aSeesB.z);
        entry.put("predicted_defender_view_y",state.bSeesA.y);entry.put("predicted_defender_view_z",state.bSeesA.z);
        entry.put("cue_applied",cue!=null&&cue.applied);entry.put("cue_needs_earlier_replay",cue!=null&&cue.needsEarlierReplay);
        entry.put("predicted_defender_view_x",state.bSeesA.x);entry.put("pending_packets",state.pendingPacketCount());
        entry.put("plan",decision.plan.stream().map(Enum::name).collect(java.util.stream.Collectors.toList()));Client.log(entry);
        snapshots.headMap(now-3000,false).clear();inputs.headMap(now-3000,false).clear();
        inputs.put(now,new Issued(decision.action,lastInjection>lastIssued));lastIssued=now;
        return decision.action;
    }
}
