import lab.ExactTravel;
import lab.Json;
import java.util.*;

/** Canonical research fight kernel. ExactTravel plus explicitly validated native event transitions.
 * Recorded clocks/transport are exogenous adapters; native later states are never corrections.
 * Recorded-schedule scope and remaining limits: see research/periodic-findings.md. */
public final class PeriodicComboFightModel {
    static final ExactTravel.Environment ENV=new ExactTravel.Environment(4,.6f,true,true,false,false,false);
    private static final ExactTravel.Environment BELOW_SUMO=new ExactTravel.Environment(-4,.6f,true,true,false,false,false);
    static final double HALF=(double)(.6f/2)+(double).1f, EYE=(double)1.62f;
    static final int R=0,W=1,S=2,BACK=3;
    static final class Body implements Cloneable {
        long localContacts;double lastHurtTime=Double.NEGATIVE_INFINITY;double lastContactTime=Double.NEGATIVE_INFINITY;ExactTravel.State q; boolean sprint, lastSent, previousForward; int tap,reportAge,jumpTicks;double reportX,reportY=4,reportZ;float yaw,pitch,headYaw,reportYaw,reportPitch,airAcceleration=.02f;
        Body(double x){reportX=x;q=state(x,4,0,-.0784000015258789,true);}
        public Body deepCopy(){try{return (Body)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        void velocity(double vx,double vy){velocity(vx,vy,q.vz());}
        void velocity(double vx,double vy,double vz){q=new ExactTravel.State(q.x(),q.y(),q.z(),vx,vy,vz,q.box(),q.onGround(),q.isCollidedHorizontally(),q.isCollidedVertically(),q.isCollided(),false,false,false);}
        void move(int key,float yaw){move(key,0,yaw);}
        void move(int key,float strafe,float yaw){move(key,strafe,yaw,false);}
        void move(int key,float strafe,float yaw,boolean jump){move(key,strafe,yaw,jump,ENV);}
        void move(int key,float strafe,float yaw,boolean jump,ExactTravel.Environment floor){
            // EntityLivingBase.onLivingUpdate decrements jumpTicks before its
            // cutoff/input/jump/travel sequence. A released key clears it below.
            if(jumpTicks>0)jumpTicks--;
            headYaw=yaw;
            if(tap>0)tap--;
            boolean forward=key==W||key==S;
            if(q.onGround()&&!previousForward&&forward&&!sprint){if(tap>0||key==S)sprint=true;else tap=7;}
            if(key==S)sprint=true;
            // EntityPlayerSP.onLivingUpdate checks the preceding travel collision after processing Ctrl.
            if(!forward||q.isCollidedHorizontally())sprint=false;
            previousForward=forward;
            q=cutoff(q);
            // Native pr.bF runs immediately before pr.g, not at the input HEAD.
            // Preserve onGround here: the takeoff travel uses ground friction.
            // Ordinary dry player only: no jump boost, fluid, mount or flight.
            if(jump&&q.onGround()&&jumpTicks==0){
                double vx=q.vx(),vz=q.vz();
                if(sprint){float angle=yaw*.017453292f;vx-=(double)(sin(angle)*.2f);vz+=(double)(cos(angle)*.2f);}
                q=motion(q,vx,(double).42f,vz);jumpTicks=10;
            }else if(!jump)jumpTicks=0;
            q=ExactTravel.predict(q,new ExactTravel.Input(strafe,key==BACK?-.98f:key==R?0:.98f,yaw,sprint?.13000001f:.1f,airAcceleration),floor);
            airAcceleration=.02f;if(sprint)airAcceleration=(float)((double)airAcceleration+(double).02f*.3D);
        }
    }
    static final class View implements Cloneable {
        double lastHurtTime=Double.NEGATIVE_INFINITY;double x,y=4,z,gx,gy=4,gz;int left;
        View(double x){this.x=this.gx=x;}
        public View deepCopy(){try{return (View)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        void receive(double x,double y){receive(x,y,0);}
        void receive(double x,double y,double z){gx=x;gy=y;gz=z;left=3;}
        void step(){if(left>0){x+=(gx-x)/left;y+=(gy-y)/left;z+=(gz-z)/left;left--;}}
    }
    static final class Packet implements Cloneable {
        int kind,packetId;boolean ground;double due,ready,x,y,z,vx,vy,vz;boolean flag;long sequence;
        Long cueDelayNanos;
        ServiceEnvelope service;
        public Packet deepCopy(){try{return (Packet)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        Packet(int k,double d,double x,double y,double vx,double vy,boolean f,long n){kind=k;due=ready=d;this.x=x;this.y=y;this.vx=vx;this.vy=vy;flag=f;sequence=n;packetId=k>=12?0x1a:k==0||k==1?3:k==2||k==9?11:k==3||k==7?2:k==4||k==8?0x12:k==10||k==11?0x1c:0x15;}
    }
    // 0 A movement, 1 B movement, 2 sprint command, 3 attack, 4 B velocity, 5 A view, 6 B view.
    public enum TimingSemantic { OTHER, OWN_HURT, TARGET_HURT, OWN_VELOCITY }
    /** One immutable generated-packet timing decision. Null optional fields retain
     * legacy readiness/publication; supplied ready is absolute, cue is drain-relative. */
    /** A measured whole-drain completion envelope, relative to actual drain start.
     * END is a lower bound on unlock, not an exact completion. Shared batch IDs
     * must be deduplicated; these are not additive per-packet CPU costs. */
    public record ServiceEnvelope(String batchId,long handlerReturnNanos,long completionLowerNanos,long completionUpperNanos) {
        public ServiceEnvelope {
            if(batchId==null||batchId.isBlank()||handlerReturnNanos<0||completionLowerNanos<handlerReturnNanos||completionUpperNanos<completionLowerNanos)
                throw new IllegalArgumentException("Invalid native service envelope");
        }
    }
    public record PacketTimingPlan(double rawReceiptMs,Double readyMs,Long cueDelayNanos,ServiceEnvelope service) {
        public PacketTimingPlan(double rawReceiptMs,Double readyMs,Long cueDelayNanos){this(rawReceiptMs,readyMs,cueDelayNanos,null);}
        public PacketTimingPlan {
            if(!Double.isFinite(rawReceiptMs)||readyMs!=null&&(!Double.isFinite(readyMs)||readyMs<rawReceiptMs)
                    ||cueDelayNanos!=null&&cueDelayNanos<0)
                throw new IllegalArgumentException("Invalid packet timing plan");
        }
    }
    /** Immutable diagnostic, never an input to physics or a legal policy. */
    public record FreshImpulse(double time,ExactTravel.State victimBefore,double x,double y,double z) {}
    // The MMC-like height cap and landing forecast follow accepted movement reports.
    record MmcMovement(double floor,double y,double dy,boolean moved,boolean hasDy,
                       boolean grounded,boolean capped) {
        static MmcMovement initial(){return new MmcMovement(4,0,0,false,false,false,false);}
        MmcMovement move(Packet p){
            boolean moving=p.packetId==4||p.packetId==6;
            return new MmcMovement(p.flag?p.y:floor,p.y,p.y-y,moving,moving&&moved,p.flag,capped&&!p.flag);
        }
        MmcMovement cap(double height){return height>.675&&!capped?new MmcMovement(floor,y,dy,moved,hasDy,grounded,true):this;}
        boolean landing(double height,double rtt){
            if(!hasDy||grounded||height<=0||height>1.3||rtt<=25)return false;
            double vy=dy;
            for(int tick=0;tick<(int)Math.ceil((rtt-25)/50);tick++){
                vy=(vy-.08)*.9800000190734863;
                height+=vy;
                if(height<=0)return true;
            }
            return false;
        }
    }
    static TimingSemantic timingSemantic(int kind){
        return switch(kind){
            case 12,13 -> TimingSemantic.TARGET_HURT;
            case 14,15 -> TimingSemantic.OWN_HURT;
            case 4,8 -> TimingSemantic.OWN_VELOCITY;
            default -> TimingSemantic.OTHER;
        };
    }
    /** Exogenous timing for generated packets. Returns network receipt,
     * not main-thread application; scheduled drains still determine task service. */
    public interface PacketTiming {
        double arrival(int route,double generatedSendMs,double nominalHalfRttMs);
        /** Optional generated semantic context; legacy implementations retain their
         * exact old behavior. No payload or historical packet identity is exposed. */
        default double arrival(int route,double generatedSendMs,double nominalHalfRttMs,TimingSemantic semantic){
            return arrival(route,generatedSendMs,nominalHalfRttMs);
        }
        default PacketTimingPlan plan(int route,double generatedSendMs,double nominalHalfRttMs,TimingSemantic semantic){
            return new PacketTimingPlan(arrival(route,generatedSendMs,nominalHalfRttMs,semantic),null,null);
        }
        PacketTiming deepCopy();
    }
    /** Opt-in diagnostic queue eligibility, separate from raw wire receipt.
     * This does NOT model queue monitors or handler service costs. Called once
     * after transport resolves arrival; only route/generated protocol subtype
     * metadata are supplied, never payloads or historical ordinals. Mutable
     * implementations must copy their state. Null retains legacy ready=due. */
    public interface PacketReadiness {
        double ready(int route,int packetId,double wireArrivalMs);
        PacketReadiness deepCopy();
    }
    static final class State implements Cloneable {
        boolean sampleCoincidence;
        long coincidenceRandom,coincidenceHits;
        void seedCoincidence(long externalSeed){
            sampleCoincidence=true;coincidenceRandom=(externalSeed^0x5DEECE66DL)&((1L<<48)-1);coincidenceHits=0;
        }
        private long randomBits(int bits){
            coincidenceRandom=(coincidenceRandom*0x5DEECE66DL+0xBL)&((1L<<48)-1);
            return coincidenceRandom>>>(48-bits);
        }
        double tieDouble(){return ((randomBits(26)<<27)+randomBits(27))*0x1.0p-53;}
        MmcMovement mmcA=MmcMovement.initial(),mmcB=MmcMovement.initial();
        double[] extraLatency={0,0,0,0},nominalHalfRtt={0,0,0,0};PacketTiming packetTiming;PacketReadiness packetReadiness;
        int plannedReadinessRoutes;
        double[] queueReadyWatermarks={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        PeriodicComboTransport transport;long tick,seq,combatOrder,lastFreshOrderA,lastFreshOrderB,lastAcceptedOrderA,lastAcceptedOrderB;int serverTicks;Body a,b;View ab,ba;ExactTravel.State serverA,serverB;
        double serverAx,serverAy=4,serverAz,serverBx,serverBy=4,serverBz,trackedAx,trackedAy=4,trackedAz,trackedBx,trackedBy=4,trackedBz;
        boolean trackedGroundA=true,trackedGroundB=true;double sentAx,sentAy=4,sentAz,sentBx,sentBy=4,sentBz;
        int teleportAgeA,teleportAgeB;int trackedYawA=-64,trackedYawB=64,trackedPitchA,trackedPitchB;double serverPitchA,serverPitchB;
        boolean serverSprint,serverSprintB,dirtyA,dirtyB,sprintDirtyA,sprintDirtyB,airborneA,airborneB;int freshThreshold=10,resistant,ownResistant,hits,sprintHits,incomingHits,immuneAttempts,acceptedA,acceptedB,lastDamageA,lastDamageB;float fallA,fallB;boolean velocityChangedA,velocityChangedB;double lastVx,lastVy,serverYawA=-90,serverYawB=90;
        double minDef=1e9,lastOwn,maxOwn,lowerOffset=-1e9,upperOffset=1e9;ArrayList<Packet> packets=new ArrayList<>(),outboundA=new ArrayList<>(),outboundB=new ArrayList<>();
        FreshImpulse lastFreshA,lastFreshB;
        State(double gap){this(0,0,gap,0);}
        State(double ax,double az,double bx,double bz){this(ax,az,bx,bz,4);}
        State(double ax,double az,double bx,double bz,double top){
            mmcA=mmcB=new MmcMovement(top,0,0,false,false,false,false);
            trackedAy=trackedBy=sentAy=sentBy=serverAy=serverBy=top;
            a=new Body(ax);b=new Body(bx);a.q=state(ax,top,az,0,-.0784000015258789,0,true);b.q=state(bx,top,bz,0,-.0784000015258789,0,true);a.reportZ=az;b.reportZ=bz;a.reportY=b.reportY=top;
            a.yaw=a.headYaw=a.reportYaw=-90;b.yaw=b.headYaw=b.reportYaw=90;
            ab=new View(bx);ab.z=ab.gz=bz;ba=new View(ax);ba.z=ba.gz=az;ab.y=ab.gy=ba.y=ba.gy=top;
            serverA=state(ax,top,az,0,-.0784000015258789,0,true);serverB=state(bx,top,bz,0,-.0784000015258789,0,true);
            serverAx=trackedAx=sentAx=ax;serverAz=trackedAz=sentAz=az;serverBx=trackedBx=sentBx=bx;serverBz=trackedBz=sentBz=bz;
        }

        /** Copies every scalar through Object.clone; mutable descendants are independently owned. */
        public State deepCopy(){
            try{State copy=(State)super.clone();copy.extraLatency=extraLatency.clone();copy.nominalHalfRtt=nominalHalfRtt.clone();copy.queueReadyWatermarks=queueReadyWatermarks.clone();
                copy.a=a.deepCopy();copy.b=b.deepCopy();copy.ab=ab.deepCopy();copy.ba=ba.deepCopy();
                copy.packets=copyPackets(packets);copy.outboundA=copyPackets(outboundA);copy.outboundB=copyPackets(outboundB);
                copy.transport=transport==null?null:transport.deepCopy();copy.packetTiming=packetTiming==null?null:packetTiming.deepCopy();copy.packetReadiness=packetReadiness==null?null:packetReadiness.deepCopy();return copy;
            }catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}
        }
        private static ArrayList<Packet> copyPackets(List<Packet> source){ArrayList<Packet> result=new ArrayList<>(source.size());for(Packet p:source)result.add(p.deepCopy());return result;}

        void add(int k,double due,double x,double y,double vx,double vy,boolean f){Packet p=new Packet(k,due,x,y,vx,vy,f,seq++);enqueue(p);}
        void add3(int k,double due,double x,double y,double z,double vx,double vy,double vz,boolean f){Packet p=new Packet(k,due,x,y,vx,vy,f,seq++);p.z=z;p.vz=vz;enqueue(p);}
        void enqueue(Packet p){
            int route=PeriodicComboTransport.route(p.kind);
            Double plannedReady=null;
            if(transport!=null&&packetTiming!=null)throw new IllegalStateException("Cannot combine recorded payload transport and empirical packet timing");
            if(transport!=null)transport.emit(p);
            else if(packetTiming!=null){
                if(extraLatency[route]!=0)throw new IllegalStateException("Empirical residual replaces extraLatency; configured RTT only");
                double send=p.due-nominalHalfRtt[route];
                TimingSemantic semantic=timingSemantic(p.kind);
                PacketTimingPlan plan=packetTiming.plan(route,send,nominalHalfRtt[route],semantic);
                if(plan==null)throw new IllegalStateException("Missing generated packet timing plan");
                if(plan.cueDelayNanos()!=null&&semantic!=TimingSemantic.OWN_HURT&&semantic!=TimingSemantic.TARGET_HURT)
                    throw new IllegalStateException("Cue delay is supported only on generated S19");
                double arrival=plan.rawReceiptMs();
                if(!Double.isFinite(arrival)||arrival<send)throw new IllegalStateException("Invalid generated packet arrival");
                p.due=arrival;plannedReady=plan.readyMs();p.cueDelayNanos=plan.cueDelayNanos();p.service=plan.service();
            }else p.due+=extraLatency[route];
            // Both recorded transport.emit and empirical timing have now resolved
            // raw receipt. Keep due intact for own PacketObservation.arrivedAt.
            p.ready=p.due;
            if(plannedReady!=null){plannedReadinessRoutes|=1<<route;p.ready=Math.max(plannedReady,queueReadyWatermarks[route]);}
            else if(packetReadiness!=null){
                double eligible=packetReadiness.ready(route,p.packetId,p.due);
                if(!Double.isFinite(eligible)||eligible<p.due)
                    throw new IllegalStateException("Invalid packet queue readiness: must be finite and at/after wire receipt");
                p.ready=Math.max(eligible,queueReadyWatermarks[route]);
            }
            if((plannedReadinessRoutes&(1<<route))!=0)p.ready=Math.max(p.ready,queueReadyWatermarks[route]);
            queueReadyWatermarks[route]=Math.max(queueReadyWatermarks[route],p.ready);
            packets.add(p);
        }
    }
    static final class Config implements Cloneable {
        public Config deepCopy(){try{return (Config)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
        boolean trackerAFirst=true;
        double centerX=4096; ExactTravel.Environment platform=ENV, below=BELOW_SUMO;
        void sumoArena(double center,double top){centerX=center;platform=new ExactTravel.Environment(top,.6f,true,true,false,false,false);below=new ExactTravel.Environment(top-8,.6f,true,true,false,false,false);}
        void solidEdge(){platform=new ExactTravel.Environment(platform.floorTopY(),.6f,true,true,false,false,false,new ExactTravel.Disk(centerX,sumoDiameter));}
        double ownRtt=20,otherRtt=20,defenderReach=1.5,kbScale=1,verticalScale=1,sprintVertical=.1;
        /** Explicit profile import keeps model/native options identical; omitted fields retain defaults. */
        void applyCombat(Properties p){
            lab.CombatProfile q=lab.CombatProfile.fromOverrides(p,new lab.CombatProfile(kbScale,verticalScale,hitTicks,sprintVertical,mmc));
            kbScale=q.horizontal();verticalScale=q.vertical();hitTicks=q.freshTicks();sprintVertical=q.sprintVertical();mmc=q.mmc();
        }
        int bPhase=10,serverPhase=25,hitTicks=10,sumoDiameter=10;
        boolean quantize=true,nativeNormalization=true,forceContacts=true,deferMovementSend=false,mmc=false,sumo=false;
    }
    // Same tile-center disk and footprint as the native sumo platform, with
    // Y translated from 12 to 4. No block side-face collision below the top.
    static boolean sumoSupport(ExactTravel.Box box,int diameter){
        return sumoSupport(box,diameter,4096);
    }
    static boolean sumoSupport(ExactTravel.Box box,int diameter,double center){
        double radius=diameter/2.0;
        for(int x=(int)Math.floor(box.minX()-center);x<box.maxX()-center;x++)
            for(int z=(int)Math.floor(box.minZ());z<box.maxZ();z++)
                if((x+.5)*(x+.5)+(z+.5)*(z+.5)<=radius*radius)return true;
        return false;
    }
    static int sumoOutcome(State s){
        boolean a=s.serverAy<3.5,b=s.serverBy<3.5;
        return a?(b?0:2):b?1:-1;
    }
    static ExactTravel.Environment floor(Config c,ExactTravel.State q){
        if(c==null||!c.sumo)return ENV;
        if(c.platform.disk()!=null)return c.platform;
        return q.y()<c.platform.floorTopY()||!sumoSupport(q.box(),c.sumoDiameter,c.centerX)?c.below:c.platform;
    }
    static ExactTravel.State state(double x,double y,double vx,double vy,boolean ground){
        return state(x,y,0,vx,vy,0,ground);
    }
    static ExactTravel.State state(double x,double y,double z,double vx,double vy,double vz,boolean ground){return new ExactTravel.State(x,y,z,vx,vy,vz,ExactTravel.boxAt(x,y,z,.6f,1.8f),ground,false,ground,ground,false,false,false);}
    static ExactTravel.State cutoff(ExactTravel.State q){return new ExactTravel.State(q.x(),q.y(),q.z(),Math.abs(q.vx())<.005?0:q.vx(),Math.abs(q.vy())<.005?0:q.vy(),Math.abs(q.vz())<.005?0:q.vz(),q.box(),q.onGround(),q.isCollidedHorizontally(),q.isCollidedVertically(),q.isCollided(),false,false,false);}
    static double quant(double x){return Math.floor(x*32)/32;}
    static double vel(double x){return (int)(Math.max(-3.9,Math.min(3.9,x))*8000)/8000.0;}
    static double range(double x,double y,double tx,double ty){double dx=Math.max(0,Math.abs(tx-x)-HALF),ey=y+EYE,dy=Math.max(0,Math.max(ty-(double).1f-ey,ey-ty-(double)1.8f-(double).1f));return Math.hypot(dx,dy);}
    static double own(State s){return nearest(s.a.q,s.ab);}
    static double their(State s){return nearest(s.b.q,s.ba);}
    static final float[] SIN=new float[65536];
    static {for(int i=0;i<SIN.length;i++)SIN[i]=(float)Math.sin((double)i*Math.PI*2.0/65536.0);}
    static float sin(float v){return SIN[(int)(v*10430.378f)&65535];}
    static float cos(float v){return SIN[(int)(v*10430.378f+16384.0f)&65535];}
    static double nearest(ExactTravel.State q,View v){double dx=Math.max(0,Math.abs(v.x-q.x())-HALF),dz=Math.max(0,Math.abs(v.z-q.z())-HALF),dy=vertical(q.y(),v.y);return Math.sqrt(dx*dx+dy*dy+dz*dz);}
    static double ray(Body b,View v){
        float cy=cos(-b.headYaw*.017453292f-(float)Math.PI),sy=sin(-b.headYaw*.017453292f-(float)Math.PI);
        float cp=-cos(-b.pitch*.017453292f),sp=sin(-b.pitch*.017453292f);
        double[] origin={b.q.x(),b.q.y()+EYE,b.q.z()},direction={(double)(sy*cp),(double)sp,(double)(cy*cp)};
        double width=(double)(.6f/2),border=(double).1f;
        double[] low={(v.x-width)-border,v.y-border,(v.z-width)-border},high={(v.x+width)+border,(v.y+(double)1.8f)+border,(v.z+width)+border};
        boolean inside=true;for(int i=0;i<3;i++)inside&=origin[i]>low[i]&&origin[i]<high[i];if(inside)return 0;
        // Native Vec3 intermediate-plane intersections use the finite 4.5-block
        // block ray. Its MathHelper look vector is not exactly unit length.
        double[] delta=new double[3];for(int i=0;i<3;i++)delta[i]=(origin[i]+direction[i]*4.5)-origin[i];
        double best=Double.POSITIVE_INFINITY;
        for(int axis=0;axis<3;axis++)for(int face=0;face<2;face++){
            if(delta[axis]*delta[axis]<1.0000000116860974E-7D)continue;
            double fraction=((face==0?low[axis]:high[axis])-origin[axis])/delta[axis];if(fraction<0||fraction>1)continue;
            double[] point={origin[0]+delta[0]*fraction,origin[1]+delta[1]*fraction,origin[2]+delta[2]*fraction};boolean contained=true;
            for(int i=0;i<3;i++)if(i!=axis&&(point[i]<low[i]||point[i]>high[i]))contained=false;
            if(contained){double dx=point[0]-origin[0],dy=point[1]-origin[1],dz=point[2]-origin[2];best=Math.min(best,dx*dx+dy*dy+dz*dz);}
        }
        return Math.sqrt(best);
    }

    static double defenderReach=1.5;
    static double vertical(double y,double ty){double ey=y+EYE;return Math.max(0,Math.max(ty-(double).1f-ey,ey-ty-(double)1.8f-(double).1f));}
    static void audit(State s){s.minDef=Math.min(s.minDef,their(s));s.maxOwn=Math.max(s.maxOwn,own(s));
        double dy=vertical(s.b.q.y(),s.ba.y);
        if(dy<defenderReach)s.lowerOffset=Math.max(s.lowerOffset,HALF+Math.sqrt(defenderReach*defenderReach-dy*dy)-(s.b.q.x()-s.ba.x));
        s.lowerOffset=Math.max(s.lowerOffset,.6-(s.b.q.x()-s.a.q.x()));
    }
    static List<Packet> due(State s,double time,int destination){ArrayList<Packet> out=new ArrayList<>();for(Iterator<Packet> it=s.packets.iterator();it.hasNext();){Packet p=it.next();int dst=p.kind<4||p.kind==7||p.kind==9?0:p.kind==5||p.kind==8||p.kind==10||p.kind==12||p.kind==14?1:2;if(dst==destination&&p.ready<=time){out.add(p);it.remove();}}out.sort(Comparator.comparingDouble((Packet p)->p.ready).thenComparingLong(p->p.sequence));return out;}
    static void receive(State s,double time,boolean a){receive(s,time,a,null);}
    /** Observer runs after this client's normal packet application. The clock adapter
     * sanitizes the packet into own-received data before exposing it to a policy. */
    static void receive(State s,double time,boolean a,java.util.function.Consumer<Packet> received){for(Packet p:due(s,time,a?1:2)){applyReceived(s,time,a,p);if(received!=null)received.accept(p);}}
    /** One canonical client application, shared by immediate legacy drains and
     * the clock's opt-in delayed S19 cue publication. No transport selection. */
    static void applyReceived(State s,double time,boolean a,Packet p){if(p.kind>=12){if(p.kind==12)s.ab.lastHurtTime=time;else if(p.kind==13)s.ba.lastHurtTime=time;else if(p.kind==14)s.a.lastHurtTime=time;else s.b.lastHurtTime=time;}else if(p.kind==4)s.b.velocity(p.vx,p.vy,p.vz);else if(p.kind==8)s.a.velocity(p.vx,p.vy,p.vz);else if(p.kind==10||p.kind==11)(a?s.a:s.b).sprint=p.flag;else {View v=a?s.ab:s.ba;if(p.flag&&Math.abs(p.x-v.x)<.03125&&Math.abs(p.y-v.y)<.015625&&Math.abs(p.z-v.z)<.03125)v.receive(v.x,v.y,v.z);else v.receive(p.x,p.y,p.z);}audit(s);}
    static void serverMoveB(State s,Packet p){serverMove(s,p,false);}
    static void serverMoveA(State s,Packet p){serverMove(s,p,true);}
    static void serverMove(State s,Packet p,boolean fromA){serverMove(s,p,fromA,null,0);}
    static void serverMove(State s,Packet p,boolean fromA,Config c,double t){
        if(c!=null&&c.mmc){
            if(fromA)s.mmcA=s.mmcA.move(p);else s.mmcB=s.mmcB.move(p);
        }
        ExactTravel.State m=fromA?s.serverA:s.serverB;
        double x=fromA?s.serverAx:s.serverBx,y=fromA?s.serverAy:s.serverBy,z=fromA?s.serverAz:s.serverBz;
        boolean sprint=fromA?s.serverSprint:s.serverSprintB;float yaw=(float)(fromA?s.serverYawA:s.serverYawB);
        m=state(x,y,z,m.vx(),m.vy(),m.vz(),m.onGround());
        m=ExactTravel.predict(cutoff(m),new ExactTravel.Input(0,0,yaw,sprint?.13000001f:.1f,.026f),floor(c,m));
        m=serverPush(s,m,fromA);
        double vx=m.vx(),vy=m.vy(),vz=m.vz();
        if(m.onGround()&&!p.flag&&p.y>y){vy=(double).42f;if(sprint){float angle=(float)p.vx*.017453292f;vx-=(double)(sin(angle)*.2f);vz+=(double)(cos(angle)*.2f);}if(fromA)s.airborneA=true;else s.airborneB=true;}
        m=state(p.x,p.y,p.z,vx,vy,vz,p.flag);
        float fall=fromA?s.fallA:s.fallB;
        if(p.flag){int damage=Math.max(0,(int)Math.ceil(fall-3.0f));if(damage>0)fallDamage(s,fromA,c!=null&&c.sumo?2*damage:damage,c,t);fall=0;}else if(p.y-y<0)fall=(float)((double)fall-(p.y-y));
        if(fromA)s.fallA=fall;else s.fallB=fall;
        if(fromA){s.serverA=m;s.serverAx=p.x;s.serverAy=p.y;s.serverAz=p.z;s.serverYawA=p.vx;s.serverPitchA=p.vy;}
        else{s.serverB=m;s.serverBx=p.x;s.serverBy=p.y;s.serverBz=p.z;s.serverYawB=p.vx;s.serverPitchB=p.vy;}
    }
    static ExactTravel.State serverPush(State s,ExactTravel.State self,boolean fromA){
        ExactTravel.State other=fromA?s.serverB:s.serverA;ExactTravel.Box a=self.box(),b=other.box();double expand=(double).2f;
        if(!(b.maxX()>a.minX()-expand&&b.minX()<a.maxX()+expand&&b.maxY()>a.minY()&&b.minY()<a.maxY()&&b.maxZ()>a.minZ()-expand&&b.minZ()<a.maxZ()+expand))return self;
        // pr.s invokes other.applyEntityCollision(self). Both reduction factors are zero here.
        double dx=self.x()-other.x(),dz=self.z()-other.z(),largest=Math.max(Math.abs(dx),Math.abs(dz));
        if(largest<(double).01f)return self;
        double root=(double)(float)Math.sqrt(largest);dx/=root;dz/=root;double inverse=1/root;if(inverse>1)inverse=1;dx*=inverse;dz*=inverse;dx*=(double).05f;dz*=(double).05f;
        other=motion(other,other.vx()-dx,other.vy(),other.vz()-dz);self=motion(self,self.vx()+dx,self.vy(),self.vz()+dz);
        if(fromA)s.serverB=other;else s.serverA=other;s.airborneA=s.airborneB=true;return self;
    }
    static ExactTravel.State motion(ExactTravel.State q,double vx,double vy,double vz){return new ExactTravel.State(q.x(),q.y(),q.z(),vx,vy,vz,q.box(),q.onGround(),q.isCollidedHorizontally(),q.isCollidedVertically(),q.isCollided(),false,false,false);}
    static void hurtStatus(State s,Config c,boolean victimA,double t){
        if(c==null)return; // Conditional server-motion helper has no packet clock.
        s.add(victimA?14:12,t+c.ownRtt/2,2,0,0,0,false);
        s.add(victimA?13:15,t+c.otherRtt/2,2,0,0,0,false);
    }
    static void fallDamage(State s,boolean victimA,int damage,Config c,double t){
        int h=victimA?s.ownResistant:s.resistant,last=victimA?s.lastDamageA:s.lastDamageB;
        if(h>s.freshThreshold&&damage<=last)return;
        if(h<=s.freshThreshold)hurtStatus(s,c,victimA,t);
        if(victimA){s.lastDamageA=damage;s.dirtyA=true;if(h<=s.freshThreshold){s.ownResistant=2*s.freshThreshold;s.velocityChangedA=true;}}
        else{s.lastDamageB=damage;s.dirtyB=true;if(h<=s.freshThreshold){s.resistant=2*s.freshThreshold;s.velocityChangedB=true;}}
    }
    static void hit(State s,Config c,boolean fromA,double t){
        ExactTravel.State attacker=fromA?s.serverA:s.serverB,victim=fromA?s.serverB:s.serverA;
        double sx=fromA?s.serverAx:s.serverBx,sy=fromA?s.serverAy:s.serverBy,sz=fromA?s.serverAz:s.serverBz;
        double tx=fromA?s.serverBx:s.serverAx,ty=fromA?s.serverBy:s.serverAy,tz=fromA?s.serverBz:s.serverAz;
        double dx=tx-sx,dz=tz-sz;if(dx*dx+(ty-sy)*(ty-sy)+dz*dz>=36)return;
        boolean critical=(fromA?s.fallA:s.fallB)>0&&!attacker.onGround();
        int damage=c.sumo?(critical?3:2):(critical?12:8);
        int immunity=fromA?s.resistant:s.ownResistant,last=fromA?s.lastDamageB:s.lastDamageA;
        if(immunity>c.hitTicks&&damage<=last){s.immuneAttempts++;return;}
        boolean fresh=immunity<=c.hitTicks,sprint=fromA?s.serverSprint:s.serverSprintB;
        double vx=victim.vx(),vy=victim.vy(),vz=victim.vz();
        if(fresh&&!c.mmc){
            if(dx*dx+dz*dz<1.0e-4){
                if(!s.sampleCoincidence)throw new IllegalStateException("Unsupported degenerate knockback separation");
                s.coincidenceHits++;
                // Vanilla samples attacker minus victim, then subtracts that direction from victim motion.
                do{
                    dx=-(s.tieDouble()-s.tieDouble())*.01;
                    dz=-(s.tieDouble()-s.tieDouble())*.01;
                }while(dx*dx+dz*dz<1.0e-4);
            }
            double length=(double)(float)Math.sqrt(dx*dx+dz*dz);
            vx=vx*.5+dx/length*(double).4f;vy=Math.min((double).4f,vy*.5+(double).4f);vz=vz*.5+dz/length*(double).4f;
        }
        if(fresh)hurtStatus(s,c,!fromA,t);
        if(sprint){float angle=(float)(fromA?s.serverYawA:s.serverYawB)*(float)Math.PI/180f;vx+=(double)(-sin(angle)*.5f);vy+=.1;vz+=(double)(cos(angle)*.5f);attacker=motion(attacker,attacker.vx()*.6,attacker.vy(),attacker.vz()*.6);}
        boolean send=fresh||(fromA?s.velocityChangedB:s.velocityChangedA);
        if(send){
            // Match the native pre-S12 intervention, including double arithmetic order.
            // Damage upgrades and unpacketized server motion retain native sprint lift.
            if(fresh&&c.mmc){
                MmcMovement target=fromA?s.mmcB:s.mmcA;
                double yaw=Math.toRadians(fromA?s.serverYawA:s.serverYawB);
                double facingX=-Math.sin(yaw),facingZ=Math.cos(yaw),length=Math.hypot(dx,dz);
                double extra=sprint?.327:0;
                vx=(length<.01?facingX:dx/length)*.5274+facingX*extra;
                vz=(length<.01?facingZ:dz/length)*.5274+facingZ*extra;
                double height=ty-target.floor();
                target=target.cap(height);
                if(fromA)s.mmcB=target;else s.mmcA=target;
                boolean landing=(!c.sumo||sumoSupport(victim.box(),c.sumoDiameter,c.centerX))&&target.landing(height,fromA?c.otherRtt:c.ownRtt);
                vy=target.capped()&&!landing?0:.361375+(sprint?c.sprintVertical:0);
            }else if(fresh&&sprint&&c.sprintVertical!=.1)vy+=c.sprintVertical-.1;
            s.lastVx=vel(vx*(fresh?c.kbScale:1));s.lastVy=vel(vy*(fresh?c.verticalScale:1));
            s.add3(fromA?4:8,t+(fromA?c.otherRtt:c.ownRtt)/2,0,0,0,s.lastVx,s.lastVy,vel(vz*(fresh?c.kbScale:1)),false);
            if(fresh){FreshImpulse impulse=new FreshImpulse(t,victim,s.lastVx,s.lastVy,vel(vz*c.kbScale));if(fromA)s.lastFreshB=impulse;else s.lastFreshA=impulse;}
        }else victim=motion(victim,vx,vy,vz);
        long acceptedOrder=++s.combatOrder;if(fromA){s.lastAcceptedOrderA=acceptedOrder;if(fresh)s.lastFreshOrderA=acceptedOrder;}else{s.lastAcceptedOrderB=acceptedOrder;if(fresh)s.lastFreshOrderB=acceptedOrder;}
        if(fromA){s.acceptedA++;s.lastDamageB=damage;s.dirtyB=true;s.serverA=attacker;s.serverB=victim;s.velocityChangedB=false;if(fresh){s.hits++;s.resistant=2*c.hitTicks;}if(fresh||sprint)s.airborneB=true;if(sprint){s.sprintHits++;s.dirtyA=true;s.sprintDirtyA=true;s.serverSprint=false;}}
        else{s.acceptedB++;s.lastDamageA=damage;s.dirtyA=true;s.serverB=attacker;s.serverA=victim;s.velocityChangedA=false;if(fresh){s.incomingHits++;s.ownResistant=2*c.hitTicks;}if(fresh||sprint)s.airborneA=true;if(sprint){s.dirtyB=true;s.sprintDirtyB=true;s.serverSprintB=false;}}
    }
    static void serverDrain(State s,Config c,double t){
        s.freshThreshold=c.hitTicks;
        for(Packet p:due(s,t,0))switch(p.kind){
            case 0:serverMove(s,p,true,c,t);break;
            case 1:serverMove(s,p,false,c,t);s.serverYawB=p.vx;break;
            case 2:s.sprintDirtyA|=s.serverSprint!=p.flag;s.dirtyA|=s.serverSprint!=p.flag;s.serverSprint=p.flag;break;
            case 3:hit(s,c,true,t);break;
            case 7:hit(s,c,false,t);break;
            case 9:s.sprintDirtyB|=s.serverSprintB!=p.flag;s.dirtyB|=s.serverSprintB!=p.flag;s.serverSprintB=p.flag;break;
            default:throw new AssertionError();
        }
    }
    static void serverTrack(State s,Config c,double t){boolean first=c.trackerAFirst;track(s,c,t,first);flushVelocity(s,c,t,first);track(s,c,t,!first);flushVelocity(s,c,t,!first);}
    static void flushVelocity(State s,Config c,double t,boolean victimA){if(!(victimA?s.velocityChangedA:s.velocityChangedB))return;ExactTravel.State q=victimA?s.serverA:s.serverB;s.add3(victimA?8:4,t+(victimA?c.ownRtt:c.otherRtt)/2,0,0,0,vel(q.vx()),vel(q.vy()),vel(q.vz()),false);if(victimA)s.velocityChangedA=false;else s.velocityChangedB=false;}
    static void track(State s,Config c,double t,boolean fromA){
        boolean dirty=fromA?s.dirtyA:s.dirtyB,air=fromA?s.airborneA:s.airborneB;
        if((s.serverTicks&1)!=0&&!dirty&&!air)return;
        int teleportAge=fromA?++s.teleportAgeA:++s.teleportAgeB;
        double x=quant(fromA?s.serverAx:s.serverBx),y=quant(fromA?s.serverAy:s.serverBy),z=quant(fromA?s.serverAz:s.serverBz);
        int yaw=(int)Math.floor((float)(fromA?s.serverYawA:s.serverYawB)*256f/360f),pitch=(int)Math.floor((float)(fromA?s.serverPitchA:s.serverPitchB)*256f/360f);
        boolean moved=Math.abs(x-(fromA?s.trackedAx:s.trackedBx))>=.125||Math.abs(y-(fromA?s.trackedAy:s.trackedBy))>=.125||Math.abs(z-(fromA?s.trackedAz:s.trackedBz))>=.125||s.serverTicks%60==0;
        boolean turned=Math.abs(yaw-(fromA?s.trackedYawA:s.trackedYawB))>=4||Math.abs(pitch-(fromA?s.trackedPitchA:s.trackedPitchB))>=4;
        boolean ground=(fromA?s.serverA:s.serverB).onGround();
        double dx=x-(fromA?s.trackedAx:s.trackedBx),dy=y-(fromA?s.trackedAy:s.trackedBy),dz=z-(fromA?s.trackedAz:s.trackedBz);
        boolean teleport=teleportAge>400||ground!=(fromA?s.trackedGroundA:s.trackedGroundB)||dx< -4||dx>=4||dy< -4||dy>=4||dz< -4||dz>=4;
        if(teleport){if(fromA)s.teleportAgeA=0;else s.teleportAgeB=0;}
        if(moved||turned||teleport){
            double gx=teleport?x:(fromA?s.sentAx:s.sentBx)+(moved?dx:0),gy=teleport?y:(fromA?s.sentAy:s.sentBy)+(moved?dy:0),gz=teleport?z:(fromA?s.sentAz:s.sentBz)+(moved?dz:0);
            Packet update=new Packet(fromA?6:5,t+(fromA?c.otherRtt:c.ownRtt)/2,gx,gy,turned||teleport?(byte)yaw:0,turned||teleport?(byte)pitch:0,teleport,s.seq++);update.z=gz;update.ground=ground;update.packetId=teleport?0x18:moved?(turned?0x17:0x15):0x16;s.enqueue(update);
            if(fromA){s.sentAx=gx;s.sentAy=gy;s.sentAz=gz;if(teleport)s.trackedGroundA=ground;}else{s.sentBx=gx;s.sentBy=gy;s.sentBz=gz;if(teleport)s.trackedGroundB=ground;}
            if(moved){if(fromA){s.trackedAx=x;s.trackedAy=y;s.trackedAz=z;}else{s.trackedBx=x;s.trackedBy=y;s.trackedBz=z;}}
            if(turned){if(fromA){s.trackedYawA=yaw;s.trackedPitchA=pitch;}else{s.trackedYawB=yaw;s.trackedPitchB=pitch;}}
        }
        if(fromA){if(s.sprintDirtyA)s.add(10,t+c.ownRtt/2,0,0,0,0,s.serverSprint);s.dirtyA=false;s.sprintDirtyA=false;s.airborneA=false;}
        else{if(s.sprintDirtyB)s.add(11,t+c.otherRtt/2,0,0,0,0,s.serverSprintB);s.dirtyB=false;s.sprintDirtyB=false;s.airborneB=false;}
    }
    static void serverEntity(State s){
        if(s.resistant>0)s.resistant--;
        if(s.ownResistant>0)s.ownResistant--;
    }
    static void serverEnd(State s){s.serverTicks++;}
    static void serverTick(State s,Config c,double t){serverDrain(s,c,t);serverEntity(s);serverTrack(s,c,t);serverEnd(s);}
    static void replayInput(State s,Config c,boolean fromA,int key,float yaw,float pitch,boolean click,double t){
        Body b=fromA?s.a:s.b;b.yaw=yaw;b.pitch=pitch;
        double distance=ray(b,fromA?s.ab:s.ba);s.lastOwn=own(s);audit(s);
        if(click&&distance<=(fromA?3:c.defenderReach)){
            b.localContacts++;b.lastContactTime=t;
            s.add(fromA?3:7,t+(fromA?c.ownRtt:c.otherRtt)/2,0,0,0,0,false);
            if(b.sprint){b.velocity(b.q.vx()*.6,b.q.vy(),b.q.vz()*.6);b.sprint=false;}
        }
    }
    static void replayMove(State s,Config c,boolean fromA,int key,float yaw,double t){replayMove(s,c,fromA,key,0,yaw,t);}
    static void replayMove(State s,Config c,boolean fromA,int key,float strafe,float yaw,double t){replayMove(s,c,fromA,key,strafe,yaw,false,t);}
    static void replayMove(State s,Config c,boolean fromA,int key,float strafe,float yaw,boolean jump,double t){
        Body b=fromA?s.a:s.b;b.move(key,strafe,yaw,jump,floor(c,b.q));audit(s);double rtt=fromA?c.ownRtt:c.otherRtt;
        ArrayList<Packet> out=fromA?s.outboundA:s.outboundB;
        if(b.sprint!=b.lastSent){out.add(new Packet(fromA?2:9,t+rtt/2,0,0,0,0,b.sprint,s.seq++));b.lastSent=b.sprint;}
        double dx=b.q.x()-b.reportX,dy=b.q.y()-b.reportY,dz=b.q.z()-b.reportZ;
        boolean moving=dx*dx+dy*dy+dz*dz>9.0E-4||b.reportAge>=20,rotating=yaw!=b.reportYaw||b.pitch!=b.reportPitch;b.reportAge++;
        if(moving){b.reportX=b.q.x();b.reportY=b.q.y();b.reportZ=b.q.z();b.reportAge=0;}
        Packet move=new Packet(fromA?0:1,t+rtt/2,b.reportX,b.reportY,yaw,b.pitch,b.q.onGround(),s.seq++);move.z=b.reportZ;move.packetId=moving?(rotating?6:4):(rotating?5:3);out.add(move);if(rotating){b.reportYaw=yaw;b.reportPitch=b.pitch;}
        if(!c.deferMovementSend)replayFlush(s,c,fromA,t);
    }
    static void replayFlush(State s,Config c,boolean fromA,double t){ArrayList<Packet> out=fromA?s.outboundA:s.outboundB;for(Packet p:out){p.due=t+(fromA?c.ownRtt:c.otherRtt)/2;s.enqueue(p);}out.clear();}
    public static void main(String[] args){throw new IllegalStateException("Cycle search retired pending coupled native validation. Use the recorded replay adapter.");}
}
