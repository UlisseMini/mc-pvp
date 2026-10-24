package lab;

import java.util.*;

/** Delayed visible-only state observer and normal-key receding-horizon controller. */
public final class ModelController {
    static final TreeMap<Long,Issued> inputs=new TreeMap<>();
    static final TreeMap<Long,Eligible> observations=new TreeMap<>();
    static final ArrayList<Particle> particles=new ArrayList<>();
    static final VisibleTracker tracker=new VisibleTracker();
    static double originX,originZ;
    static long lastInjection,lastIssued,lastEligibleTick=-1,lastHurtCue;
    static Client.Observation previousEligible;
    static final class Issued {
        final ComboModel.Action action;final boolean click;
        Issued(ComboModel.Action action,boolean click){this.action=action;this.click=click;}
    }
    static final class Eligible {
        final Client.Observation obs;final VisibleTracker.Estimate estimate;final long cue;
        Eligible(Client.Observation obs,VisibleTracker.Estimate estimate,long cue){this.obs=obs;this.estimate=estimate;this.cue=cue;}
    }
    static final class Particle {
        final ComboModel.Config config=new ComboModel.Config();
        final TreeMap<Long,ComboModel.State> snapshots=new TreeMap<>();
        final TreeMap<Long,Long> confirmed=new TreeMap<>();
        long processed=Long.MIN_VALUE,lastGoalSource=Long.MIN_VALUE;
        double loss,ownError,renderError,goalError,anchorError;
        ComboModel.State current;
        Particle(int server,int opponent,int tracking){config.serverPhaseMs=server;config.opponentPhaseMs=opponent;config.trackerPhase=tracking;}
    }
    static void reset(){
        inputs.clear();observations.clear();particles.clear();tracker.reset();previousEligible=null;
        lastInjection=lastIssued=lastHurtCue=0;lastEligibleTick=-1;LegacyModelController.reset();
    }
    static void click(long time){lastInjection=time;LegacyModelController.click(time);}
    static void idle(long time){
        inputs.put(time,new Issued(ComboModel.Action.RELEASE,false));lastIssued=time;
        inputs.headMap(time-3000,false).clear();LegacyModelController.idle(time);
    }
    static void observeEligible(Client.Observation obs){
        if(obs.tick<=lastEligibleTick)return;
        if(obs.targetHurt>0&&(previousEligible==null||obs.targetHurt>previousEligible.targetHurt))lastHurtCue=obs.time;
        observations.put(obs.time,new Eligible(obs,tracker.update(obs.tick,obs.time,obs.tx,obs.ty,obs.tz),lastHurtCue));
        lastEligibleTick=obs.tick;previousEligible=obs;
    }
    static void configure(ComboModel.Config c){
        c.ownRttMs=Client.number("ping",0);c.opponentRttMs=Client.number("opponent_ping",0);
        c.defenderControlAtMs=Client.epoch+150;
        c.horizonTicks=(int)Client.number("model_horizon_ticks",26);c.beamWidth=(int)Client.number("model_beam_width",24);
        c.minimumActionTicks=(int)Client.number("model_min_action_ticks",2);c.clickIntervalMs=(int)Client.number("model_click_ms",105);
        c.hitReward=Client.number("hit_reward",1000);c.opportunityPenalty=Client.number("opportunity_penalty",300);
        c.penetrationPenalty=Client.number("penetration_penalty",300);
        c.oneSidedReward=Client.number("one_sided_reward",0);c.mutualOpportunityPenalty=Client.number("mutual_opportunity_penalty",0);
        c.contactEdgePenalty=Client.number("contact_edge_penalty",0);
        c.dynamicServerImpulses=Client.number("dynamic_server_impulses",1)>0;
    }
    static ComboModel.State initial(Client.Observation obs){
        double x=obs.x-originX,z=obs.z-originZ,tx=obs.tx-originX,tz=obs.tz-originZ,y=Math.max(0,obs.y-4),ty=Math.max(0,obs.ty-4);
        return new ComboModel.State(obs.time,
            new ComboModel.Body(x,y,z,obs.vx,obs.vy,obs.vz,obs.ground,obs.sprint),
            new ComboModel.Body(tx,ty,tz,0,-.0784,0,ty<=.01,false),
            new ComboModel.View(tx,ty,tz),new ComboModel.View(x,y,z),new ComboModel.View(x,y,z),new ComboModel.View(tx,ty,tz));
    }
    static void assimilate(ComboModel.State s,Eligible e,ComboModel.Config c){
        Client.Observation o=e.obs;
        s.correctOwn(o.x-originX,Math.max(0,o.y-4),o.z-originZ,o.vx,o.vy,o.vz,o.ground,o.sprint);
        s.aLastSentSprint=o.sentSprint;
        // These are reconstructed from visible motion; packet/motion fields of
        // the other client are never an input to this controller.
        s.aSeesB.x=o.tx-originX;s.aSeesB.y=Math.max(0,o.ty-4);s.aSeesB.z=o.tz-originZ;
        VisibleTracker.Estimate v=e.estimate;
        if(v.valid){
            s.aSeesB.goalX=v.goalX-originX;s.aSeesB.goalY=Math.max(0,v.goalY-4);s.aSeesB.goalZ=v.goalZ-originZ;
            s.aSeesB.interpolationTicks=v.remainingAfter;
        }
        // Hit timing/impulses are reconciled by historical replay, not by
        // applying a new impulse or the latest possible hit time at this view.
        if(e.cue>0&&e.cue<=s.nowMs)s.lastAssimilatedCueMs=e.cue;
    }
    static ComboModel.State replay(Particle p,ComboModel.State state,long until){
        for(long hit:p.confirmed.values())if(hit>=state.nowMs)state.scheduleConfirmedHit(hit,p.config);
        for(var issued:new ArrayList<>(inputs.subMap(state.nowMs,true,until,false).entrySet())){
            state.nowMs=issued.getKey();
            Eligible e=observations.get(state.nowMs);if(e!=null)assimilate(state,e,p.config);
            p.snapshots.put(state.nowMs,state.copy());
            state=ComboModel.advanceRecorded(state,issued.getValue().action,issued.getValue().click,p.config);
        }
        state.nowMs=until;return state;
    }
    static void update(Particle p,Eligible e){
        Client.Observation o=e.obs;VisibleTracker.Estimate v=e.estimate;
        Map.Entry<Long,ComboModel.State> obsAnchor=p.snapshots.floorEntry(o.time);
        if(obsAnchor==null){
            ComboModel.State state=initial(o);assimilate(state,e,p.config);p.snapshots.put(o.time,state);p.processed=o.time;return;
        }
        ComboModel.State predicted=obsAnchor.getValue();
        p.ownError=Math.sqrt(Math.pow(o.x-originX-predicted.a.x,2)+Math.pow(o.y-4-predicted.a.y,2)+Math.pow(o.z-originZ-predicted.a.z,2));
        double dx=o.tx-originX-predicted.aSeesB.x,dy=o.ty-4-predicted.aSeesB.y,dz=o.tz-originZ-predicted.aSeesB.z;
        p.renderError=Math.sqrt(dx*dx+dy*dy+dz*dz);
        p.goalError=v.valid?Math.sqrt(Math.pow(v.goalX-originX-predicted.aSeesB.goalX,2)+Math.pow(v.goalY-4-predicted.aSeesB.goalY,2)+Math.pow(v.goalZ-originZ-predicted.aSeesB.goalZ,2)):0;
        double loss=p.renderError*p.renderError+(v.highConfidence()?.3*p.goalError*p.goalError:0);
        p.loss=.94*p.loss+.06*Math.min(16,loss);
        ComboModel.State state=predicted.copy();p.anchorError=0;
        double source=v.sourceTimeMs(p.config.ownRttMs,p.config.opponentRttMs)+Client.number("goal_source_shift_ms",0);
        if(v.goalChanged&&v.highConfidence()&&v.timingEstablished&&Double.isFinite(source)){
            Map.Entry<Long,ComboModel.State> sourceAnchor=null;double best=Double.POSITIVE_INFINITY;
            // B in State(T) is after its previous tick at T-50+phase.
            for(var entry:p.snapshots.headMap(o.time,true).entrySet()){
                double error=Math.abs(entry.getKey()-50+p.config.opponentPhaseMs-source);
                if(error<best){best=error;sourceAnchor=entry;}
            }
            if(sourceAnchor!=null&&best<=75){
                state=sourceAnchor.getValue().copy();
                // A quantized coordinate is an interval. Preserve an estimate
                // already inside it, including exact Z=0 and floor height.
                double ex=binResidual(state.b.x+originX,v.goalX),ey=binResidual(state.b.y+4,v.goalY),ez=binResidual(state.b.z+originZ,v.goalZ);
                p.anchorError=Math.sqrt(ex*ex+ey*ey+ez*ez);
                p.loss+=.06*Client.number("anchor_fit_weight",0)*Math.min(16,p.anchorError*p.anchorError);
                state.translateDefenderTrace(ex,ey,ez);
                double dt=Math.max(1,(source-p.lastGoalSource)/50.0),gain=Client.number("anchor_velocity_gain",.5);
                if(p.lastGoalSource!=Long.MIN_VALUE&&dt<=10){
                    state.b.vx=Math.max(-1.3,Math.min(1.3,state.b.vx+gain*ex/dt));
                    state.b.vy=Math.max(-1.5,Math.min(.6,state.b.vy+gain*ey/dt));
                    state.b.vz=Math.max(-1.3,Math.min(1.3,state.b.vz+gain*ez/dt));
                }
                p.lastGoalSource=Math.round(source);
                p.snapshots.tailMap(state.nowMs,true).clear();
                state=replay(p,state,o.time);
            }
        }
        if(e.cue>0&&!p.confirmed.containsKey(e.cue)){
            double upper=e.cue-p.config.ownRttMs/2,lower=upper-50;
            long hit;
            if(predicted.lastPredictedHitMs>=lower&&predicted.lastPredictedHitMs<=upper)hit=predicted.lastPredictedHitMs;
            else {
                hit=Math.round((lower+upper)/2);double error=Double.POSITIVE_INFINITY;
                for(long tick:inputs.headMap(o.time,true).keySet()){
                    long candidate=tick+p.config.serverPhaseMs;
                    if(candidate>=lower&&candidate<=upper&&Math.abs(candidate-(lower+upper)/2)<error){hit=candidate;error=Math.abs(candidate-(lower+upper)/2);}
                }
            }
            p.confirmed.put(e.cue,hit);
            var rewind=p.snapshots.lowerEntry(hit-100);
            if(rewind!=null){
                state=rewind.getValue().copy();p.snapshots.tailMap(state.nowMs,true).clear();
                state=replay(p,state,o.time);
            }
        }
        state.nowMs=o.time;assimilate(state,e,p.config);
        p.snapshots.tailMap(o.time,true).clear();p.snapshots.put(o.time,state.copy());p.processed=o.time;
    }
    static double binResidual(double predicted,double quantized){return Math.max(quantized,Math.min(quantized+1.0/32-1e-9,predicted))-predicted;}
    static ComboModel.Action decide(Client.Observation obs,long now){
        if(Client.controls.getProperty("observer","legacy").equals("legacy"))return LegacyModelController.decide(obs,now);
        if(particles.isEmpty()){
            Client.Observation first=observations.firstEntry().getValue().obs;originX=first.x;originZ=first.z;
            if(Client.controls.getProperty("observer").equals("visible"))particles.add(new Particle(25,10,0));
            else for(int server:new int[]{5,25,45})for(int opponent:new int[]{5,25,45})for(int tracking=0;tracking<2;tracking++)particles.add(new Particle(server,opponent,tracking));
        }
        for(Particle p:particles){
            configure(p.config);
            for(Eligible e:new ArrayList<>(observations.tailMap(p.processed,false).values())){
                if(e.obs.time>obs.time)break;
                if(!p.snapshots.isEmpty()){
                    var anchor=p.snapshots.floorEntry(e.obs.time);
                    if(anchor!=null&&!p.snapshots.containsKey(e.obs.time))p.snapshots.put(e.obs.time,replay(p,anchor.getValue().copy(),e.obs.time));
                }
                update(p,e);
            }
            p.current=replay(p,p.snapshots.floorEntry(obs.time).getValue().copy(),now);
            p.current.alignPhysicalClickClock(lastInjection,p.config);
            if(lastInjection>lastIssued)p.current.nextPhysicalClickMs=now;
            p.snapshots.put(now,p.current.copy());p.snapshots.headMap(now-3000,false).clear();
        }
        ArrayList<Particle> ranked=new ArrayList<>(particles);ranked.sort(Comparator.comparingDouble(p->p.loss));
        Particle best=ranked.get(0);ComboModel.State state=best.current;
        ComboModel.Decision decision=ComboModel.choose(state,best.config);
        Eligible latest=observations.floorEntry(obs.time).getValue();VisibleTracker.Estimate v=latest.estimate;
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("type","mpc_plan");entry.put("time",now);entry.put("observation_time",obs.time);entry.put("match",Client.match);
        entry.put("observer",Client.controls.getProperty("observer"));entry.put("action",decision.action.name());entry.put("predicted_hits",decision.predictedHits);
        entry.put("predicted_defender_opportunity_ticks",decision.predictedDefenderOpportunityTicks);entry.put("score",decision.score);
        int can=decision.predictedEnd.attackerOpportunityTicks-state.attackerOpportunityTicks,one=decision.predictedEnd.oneSidedOpportunityTicks-state.oneSidedOpportunityTicks;
        entry.put("predicted_attacker_opportunity_ticks",can);entry.put("predicted_one_sided_ticks",one);entry.put("predicted_one_sided_fraction",can>0?(double)one/can:0);
        entry.put("own_observation_error",best.ownError);entry.put("own_error_measured",true);entry.put("view_observation_error",best.renderError);entry.put("goal_observation_error",best.goalError);entry.put("body_anchor_error",best.anchorError);
        entry.put("visible_goal_confidence",v.confidence.name());entry.put("visible_goal_source_time",v.valid&&v.firstUseTimeMs!=Long.MIN_VALUE?v.sourceTimeMs(best.config.ownRttMs,best.config.opponentRttMs):null);
        entry.put("predicted_own_x",state.a.x);entry.put("predicted_own_y",state.a.y);entry.put("predicted_own_z",state.a.z);
        entry.put("predicted_target_x",state.b.x);entry.put("predicted_target_y",state.b.y);entry.put("predicted_target_z",state.b.z);
        entry.put("predicted_target_vx",state.b.vx);entry.put("predicted_target_vy",state.b.vy);entry.put("predicted_target_vz",state.b.vz);
        entry.put("predicted_target_view_x",state.aSeesB.x);entry.put("predicted_target_view_y",state.aSeesB.y);entry.put("predicted_target_view_z",state.aSeesB.z);
        entry.put("predicted_defender_view_x",state.bSeesA.x);entry.put("predicted_defender_view_y",state.bSeesA.y);entry.put("predicted_defender_view_z",state.bSeesA.z);
        entry.put("phase_hypotheses",ranked.subList(0,Math.min(3,ranked.size())).stream().map(p->Map.of("server",p.config.serverPhaseMs,"opponent",p.config.opponentPhaseMs,"tracker",p.config.trackerPhase,"loss",p.loss)).toList());
        entry.put("pending_packets",state.pendingPacketCount());entry.put("plan",decision.plan.stream().map(Enum::name).toList());Client.log(entry);
        inputs.put(now,new Issued(decision.action,lastInjection>lastIssued));lastIssued=now;
        inputs.headMap(now-3000,false).clear();observations.headMap(now-3000,false).clear();
        return decision.action;
    }
}
