import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lab.ExactTravel;

/** Flat-floor reach forecasts from one client's own state and received position reports. */
final class BoxingFollowupProjection {
    private static final int STEPS = 14;

    private record Ranges(double own, double defender) {}
    private record Location(double x,double y,double z) {
        static Location at(ExactTravel.State s) { return new Location(s.x(),s.y(),s.z()); }
        PeriodicComboFightModel.View view() {
            var v=new PeriodicComboFightModel.View(x);
            v.y=y;
            v.z=z;
            return v;
        }
    }

    private static PeriodicComboFightModel.Body ownBody(PeriodicComboClock.Observation o) {
        var p=new PeriodicComboFightModel.Body(o.own().x());
        p.q=o.own();
        p.sprint=o.sprint();
        p.previousForward=o.sprint();
        p.airAcceleration=o.sprint()?.026f:.02f;
        return p;
    }

    private static PeriodicComboFightModel.Body target(PeriodicComboClock.Observation o,
                         List<PeriodicComboClock.PacketObservation> reports, double assumedImpulse, int rtt) {
        double x=o.targetX(),y=o.targetY(),z=o.targetZ();
        double vx=0,vy=0,vz=0;
        var current=reports.isEmpty()?null:reports.get(reports.size()-1);
        if (current!=null) {
            x=current.goalX();y=current.goalY();z=current.goalZ();
            for (int i=reports.size()-2;i>=0;i--) {
                var older=reports.get(i);
                double dt=current.arrivedAt()-older.arrivedAt();
                if (dt<30) continue;
                if (dt<=160) {
                    vx=(x-older.goalX())*50/dt*.82;
                    vz=(z-older.goalZ())*50/dt*.82;
                    vy=((y-older.goalY())*50/dt-.08)*.98;
                }
                break;
            }
        }
        var p=new PeriodicComboFightModel.Body(x);
        boolean grounded=y<=4.002;
        if (grounded && o.time()-o.lastTargetHurtTime()<130) {
            double dx=x-o.own().x(), dz=z-o.own().z();
            double len=Math.max(.001,Math.hypot(dx,dz));
            vx=assumedImpulse*dx/len;
            vz=assumedImpulse*dz/len;
            vy=.361375;
        } else if (grounded) vy=-.0784;
        p.q=PeriodicComboFightModel.state(x,Math.max(4.,y),z,vx,vy,vz,grounded);
        p.sprint=true;
        p.airAcceleration=.026f;
        // A handled replica report can already be an old target pose. These are estimated
        // steps from the report to now, not an observed remote input phase.
        if (current!=null && !grounded) {
            int steps=(int)Math.max(0,Math.min(3,Math.round((o.time()-current.arrivedAt()+rtt*.5)/50)));
            for (int i=0;i<steps;i++) p.move(PeriodicComboFightModel.S,yaw(p.q,Location.at(o.own())));
        }
        return p;
    }

    private static float yaw(ExactTravel.State source, Location target) {
        return (float)Math.toDegrees(Math.atan2(source.x()-target.x,target.z-source.z()));
    }

    private static void step(PeriodicComboFightModel.Body p, Location other, int key, boolean brake, boolean forcedBrake) {
        if (forcedBrake || (brake && p.sprint && PeriodicComboFightModel.nearest(p.q,other.view())<=3)) {
            p.velocity(p.q.vx()*.6,p.q.vy(),p.q.vz()*.6);
            p.sprint=false;
        }
        p.move(key,yaw(p.q,other));
    }

    private static List<Ranges> roll(PeriodicComboFightModel.Body self, PeriodicComboFightModel.Body opponent,
                                     Location targetAtStart, Location ownAtStart, int pulse, int releaseTicks,
                                     int defenderReleaseTicks, int lag, boolean brake, List<List<Integer>> forcedBrakes) {
        var ownHistory=new ArrayList<Location>();
        var targetHistory=new ArrayList<Location>();
        ownHistory.add(Location.at(self.q));
        targetHistory.add(Location.at(opponent.q));
        var output=new ArrayList<Ranges>();
        for (int at=0;at<=STEPS;at++) {
            int past=Math.max(0,at-lag);
            Location theirImage=at<lag?targetAtStart:targetHistory.get(past);
            Location ourImage=at<lag?ownAtStart:ownHistory.get(past);
            output.add(new Ranges(PeriodicComboFightModel.nearest(self.q,theirImage.view()),
                    PeriodicComboFightModel.nearest(opponent.q,ourImage.view())));
            int ownKey=at<releaseTicks && at>=pulse?PeriodicComboFightModel.R:PeriodicComboFightModel.S;
            step(self,theirImage,ownKey,brake,forcedBrakes.get(0).contains(at));
            step(opponent,ourImage,at<defenderReleaseTicks?PeriodicComboFightModel.R:PeriodicComboFightModel.S,
                    brake,forcedBrakes.get(1).contains(at));
            ownHistory.add(Location.at(self.q));
            targetHistory.add(Location.at(opponent.q));
        }
        return output;
    }

    private static List<Ranges> roll(PeriodicComboClock.Observation o, List<PeriodicComboClock.PacketObservation> reports,
                                     int rtt, int pulse, int releaseTicks, int lag, double assumedImpulse, boolean brake) {
        return roll(ownBody(o),target(o,reports,assumedImpulse,rtt),new Location(o.targetX(),o.targetY(),o.targetZ()),
                Location.at(o.own()),pulse,releaseTicks,0,lag,brake,List.of(List.of(),List.of()));
    }

    private static int at(double millis) {
        return (int)Math.max(0,Math.min(STEPS,Math.round(millis/50)));
    }

    static Map<String,Object> predict(PeriodicComboClock.Observation o, List<PeriodicComboClock.PacketObservation> reports,
                                      int rtt, int pulse, boolean stockRelease) {
        // Estimate each original server deadline from that player's locally received hurt cue.
        double own=500-(o.time()-o.lastTargetHurtTime())-rtt*.5-25;
        double defender=500-(o.time()-o.lastOwnHurtTime())-rtt*.5-25;
        int defaultLag=1+(int)Math.round(rtt/50.0);
        var nominal=roll(o,reports,rtt,pulse,stockRelease?1:0,defaultLag,.9495,true);
        var a=new ArrayList<Double>();
        var b=new ArrayList<Double>();
        for (int lag=Math.max(1,defaultLag-1);lag<=defaultLag+1;lag++) {
            for (double impulse:new double[]{.5275,.9495}) for (boolean brake:new boolean[]{false,true}) {
                for (int w:stockRelease?new int[]{1,2}:new int[]{0}) {
                    var p=roll(o,reports,rtt,pulse,w,lag,impulse,brake);
                    for (int dt:new int[]{-25,0,25}) {
                        a.add(p.get(at(own+dt)).own);
                        b.add(p.get(at(defender+dt)).defender);
                    }
                }
            }
        }
        return Map.of("clock",List.of(own,defender), "stock_release",stockRelease,
                "nominal",List.of(nominal.get(at(own)).own,nominal.get(at(defender)).defender),
                "nominal_path",nominal.stream().map(r->List.of(r.own,r.defender)).toList(),
                "no_brake_path",roll(o,reports,rtt,pulse,stockRelease?1:0,defaultLag,.9495,false).stream()
                        .map(r->List.of(r.own,r.defender)).toList(),
                "own_range",List.of(a.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                                    a.stream().mapToDouble(Double::doubleValue).max().orElseThrow()),
                "defender_range",List.of(b.stream().mapToDouble(Double::doubleValue).min().orElseThrow(),
                                         b.stream().mapToDouble(Double::doubleValue).max().orElseThrow()));
    }

    /** Offline-only ablation. Actual bodies and current replicas substitute for the observation estimator. */
    static Map<String,Object> actualStart(DuelEnv.Game game, int rtt, int pulse, boolean stockRelease,
                                          List<List<Integer>> appliedBrakes) {
        var s=game.clock.state;
        var own=game.first?s.a:s.b;
        var opponent=game.first?s.b:s.a;
        var targetView=game.first?s.ab:s.ba;
        var ownView=game.first?s.ba:s.ab;
        var other=game.first?game.b:game.a;
        boolean defenderRelease=ownView.lastHurtTime>other.handled || (long)game.clock.time()<other.releaseUntil;
        int lag=1+(int)Math.round(rtt/50.0);
        var output=new java.util.LinkedHashMap<String,Object>();
        for (String drag:List.of("brake","no_brake","observed_brake")) {
            var paths=roll(own.deepCopy(),opponent.deepCopy(),new Location(targetView.x,targetView.y,targetView.z),
                    new Location(ownView.x,ownView.y,ownView.z),pulse,stockRelease?1:0,defenderRelease?1:0,lag,
                    drag.equals("brake"),drag.equals("observed_brake")?appliedBrakes:List.of(List.of(),List.of()));
            output.put(drag,paths.stream().map(r->List.of(r.own,r.defender)).toList());
        }
        return output;
    }
}
