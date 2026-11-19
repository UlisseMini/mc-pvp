import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.ClickRate;
import lab.MomentumController;

/** Portable subset of older sumo controls; rejects unsupported overrides instead of substituting a bot. */
final class HistoricalMovement extends DuelEnv.Player {
    static final long ORIGIN=150_000;
    static final Set<String> KINDS=Set.of("momentum","spacing","predictive","phase","periodic","hitreset","wtap","hold","standstill","tradetap","zest","scripted");
    static final Set<String> KEYS=Set.of("kind","description","reaction_ms","fast_view","attack_reach","click_cps","click_mode",
        "click_min_interval_ms","click_start_ms","sprint_mode","target_hurt_cue","pause_ms","period_ms","gap","margin","horizon_ms",
        "trade_window_ms","reply_wait_ms","reset_every","reset_delay_ms","reset_fresh_phase","reset_on_issued_click",
        "reset_rtt_adjusted","reset_walk","pause_ticks","adaptive_reset","opening_wait_ms","fresh_correction_ms","input_script","burst_on_ms","burst_period_ms",
        "cycle_hits","entry","entry_timeout_ms","hit_window_ms","interpolation_ms","lock_control","lock_margin","opening_range",
        "recover","release_lead_ms","release_ms","select_delay_ms","spacing","spacing_after_ms",
        "strafe","strafe_during_release_only","strafe_period_ms","window_bias","zest_gap","spacing_horizon_ms","control_horizon_ms",
        "deny_margin","chase_speed","lock_hysteresis","emergency_tap_ms","opening_margin");
    static final Map<String,Properties> CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    static Properties settings(String n){
        if(!n.matches("[a-z0-9_-]+"))throw new IllegalArgumentException("Invalid historical alias");
        return CACHE.computeIfAbsent(n,name->{
            var p=new Properties();
            try(var in=Files.newInputStream(Path.of("policies",name+".properties"))){p.load(in);}
            catch(IOException ex){throw new UncheckedIOException(ex);}
            p.setProperty("reaction_ms","0");p.setProperty("click_cps","20");
            if(!supports(p))throw new IllegalArgumentException("Unsupported historical controller "+name);
            return p;
        });
    }
    static boolean supports(Properties p){
        if(!KINDS.contains(p.getProperty("kind","hold"))||!Set.of("fixed","none","burst").contains(p.getProperty("click_mode","fixed")))return false;
        if(!Set.of("hold","walk","cycle","phase").contains(p.getProperty("sprint_mode","hold")))return false;
        for(String k:p.stringPropertyNames())
            if(!KEYS.contains(k)&&!k.startsWith("aim_")&&!k.startsWith("science_")&&!k.startsWith("momentum_"))return false;
        return true;
    }
    record Head(long now,long epoch,long observed,long incoming,long landed,long lastClick,
                double x,double y,double z,double tx,double ty,double tz,double vx,double vz,boolean ground,
                double ux,double uz,double tvx,double tvz,double closing){}
    private final Properties p;private final String name,kind;
    private final MomentumController.Settings movement;
    private MomentumController momentum=new MomentumController();
    private HistoricalZest zest;
    private final int[][] script;
    private long tapUntil,handledCue,lastHead,lastClickWall;
    private double lastClickAt=Double.NEGATIVE_INFINITY,seenIn=Double.NEGATIVE_INFINITY,seenOut=Double.NEGATIVE_INFINITY;
    private long incoming,landed;
    private boolean forward,pulse=true;
    private double oldX,oldZ,oldTx,oldTz,oldDistance,tvx,tvz;
    private final int ownRtt,otherRtt;
    HistoricalMovement(String n,int r,int other){
        super("historical",0,r,0);name=n;p=settings(n);kind=p.getProperty("kind");ownRtt=r;otherRtt=other;
        movement=MomentumController.Settings.from(p);zest=new HistoricalZest(p);
        script=Arrays.stream(p.getProperty("input_script","0:0:0:0").split("\\|"))
                .map(row->Arrays.stream(row.split(":")).mapToInt(Integer::parseInt).toArray()).toArray(int[][]::new);
    }
    private HistoricalMovement(HistoricalMovement s){
        super("historical",0,s.rtt,0);name=s.name;p=s.p;kind=s.kind;ownRtt=s.ownRtt;otherRtt=s.otherRtt;movement=s.movement;
        zest=s.zest.copy();script=s.script;momentum=s.momentum.copy();tapUntil=s.tapUntil;handledCue=s.handledCue;lastHead=s.lastHead;lastClickWall=s.lastClickWall;
        lastClickAt=s.lastClickAt;seenIn=s.seenIn;seenOut=s.seenOut;incoming=s.incoming;landed=s.landed;forward=s.forward;pulse=s.pulse;
        oldX=s.oldX;oldZ=s.oldZ;oldTx=s.oldTx;oldTz=s.oldTz;oldDistance=s.oldDistance;tvx=s.tvx;tvz=s.tvz;firstClick=s.firstClick;
    }
    @Override HistoricalMovement copy(){return new HistoricalMovement(this);}
    double val(String k,double fallback){String s=p.getProperty(k);return s==null?fallback:Double.parseDouble(s);}
    PeriodicComboClock.Control decide(Head h){
        boolean go=true,ctrl=true,click=true;int lateral=0;double dx=h.tx-h.x,dz=h.tz-h.z;
        double distance=Math.hypot(dx,dz),fx=dx/Math.max(.001,distance),fz=dz/Math.max(.001,distance);
        double motion=h.vx*fx+h.vz*fz;
        if(h.lastClick!=lastClickWall && p.getProperty("sprint_mode","hold").equals("cycle"))pulse=false;
        lastClickWall=h.lastClick;
        switch(kind){
            case "scripted" -> {
                go=false;ctrl=false;
                for(int[] row:script)if(h.now-h.epoch>=row[0]){go=row[1]!=0;ctrl=row[2]!=0;lateral=row[3];}
            }
            case "zest" -> {
                var d=zest.decide(h,ownRtt,otherRtt,forward);go=d.forward();lateral=d.side();click=d.click();
            }
            case "momentum" -> {
                var d=momentum.decide(new MomentumController.Input(h.now,h.observed,h.landed,h.incoming,h.lastClick,distance,
                    h.ux*fx+h.uz*fz,h.tvx*fx+h.tvz*fz,motion,h.y,h.ty,h.ground,ownRtt,otherRtt),movement);
                go=d.forward();ctrl=d.sprintKey();lateral=d.lateral();
            }
            case "spacing","predictive","phase" -> {
                double horizon=(h.now-h.observed+val("horizon_ms",80))/1000.;
                if(kind.equals("phase"))horizon=(h.now-h.observed+Math.min(500,Math.max(0,h.landed-ownRtt+450-h.now)))/1000.;
                double projected=distance-Math.max(-8,Math.min(8,h.closing))*horizon-Math.max(0,motion)/(1-.546);
                if(kind.equals("predictive"))projected-=(ownRtt+otherRtt)*Math.max(0,h.closing)/4000.;
                go=projected>val("gap",2.9)+(forward?-1:1)*val("margin",.10);
            }
            case "wtap","tradetap" -> {
                if(h.landed>handledCue && (kind.equals("wtap") || h.incoming>=h.landed-val("trade_window_ms",200)
                        || h.now-h.landed>=val("reply_wait_ms",150)+otherRtt/2.)){
                    tapUntil=h.now+(long)val("pause_ms",50);handledCue=h.landed;
                }
                go=h.now>=tapUntil;
            }
            case "hitreset" -> go=h.now-h.landed>val("pause_ms",50);
            case "periodic" -> go=(h.now-h.epoch)%(long)val("period_ms",400)>=(long)val("pause_ms",50);
            case "standstill" -> go=false;
            default -> {if(!kind.equals("hold"))throw new AssertionError(kind);}
        }
        if(!kind.equals("momentum") && !kind.equals("scripted")){
            if(go&&!forward)pulse=true;
            String sprint=p.getProperty("sprint_mode","hold");ctrl=!sprint.equals("walk")&&(!sprint.equals("cycle")||pulse) && (!sprint.equals("phase")||zest.phase==0);
        }
        forward=go;
        click &= !p.getProperty("click_mode","fixed").equals("none") &&
                (!p.containsKey("click_start_ms") || h.now>=h.epoch+val("click_start_ms",0));
        if(p.getProperty("click_mode","fixed").equals("burst"))
            click=(h.now-h.epoch)%(long)val("burst_period_ms",500)<val("burst_on_ms",300);
        return new PeriodicComboClock.Control(go?(ctrl?2:1):0,lateral*-.98f,click,false);
    }
    @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
        var q=o.own();long now=ORIGIN+(long)o.time();double dist=Math.hypot(o.targetX()-q.x(),o.targetZ()-q.z());
        double ux=0,uz=0,closing=0;
        if(lastHead>0 && now>lastHead){
            double dt=(now-lastHead)/1000.;ux=(q.x()-oldX)/dt;uz=(q.z()-oldZ)/dt;
            tvx=.5*tvx+.5*(o.targetX()-oldTx)/dt;tvz=.5*tvz+.5*(o.targetZ()-oldTz)/dt;closing=(oldDistance-dist)/dt;
        }else{tvx=0;tvz=0;}
        if(o.lastOwnHurtTime()>seenIn){incoming=now;seenIn=o.lastOwnHurtTime();}
        if(o.lastTargetHurtTime()>seenOut){
            landed=p.getProperty("target_hurt_cue","sampled").equals("status")?ORIGIN+(long)o.lastTargetHurtTime():now;
            seenOut=o.lastTargetHurtTime();
        }
        long clickWall=Double.isFinite(lastClickAt)?ORIGIN+(long)lastClickAt:0;
        var action=decide(new Head(now,ORIGIN,now,incoming,landed,clickWall,q.x(),q.y(),q.z(),o.targetX(),o.targetY(),o.targetZ(),
                                   q.vx(),q.vz(),q.onGround(),ux,uz,tvx,tvz,closing));
        lastHead=now;oldX=q.x();oldZ=q.z();oldTx=o.targetX();oldTz=o.targetZ();oldDistance=dist;
        return action;
    }
    @Override public boolean injected(PeriodicComboClock.Observation o,double t){lastClickAt=t;return super.injected(o,t);}
    @Override public boolean allowInjection(PeriodicComboClock.Observation o,String kind){
        long interval=ClickRate.intervalNanos(p);
        // The environment already applies the shared physical 20-CPS ceiling in its own clock.
        return interval<=50_000_000L || (o.time()-lastClickAt)*1e6>=interval;
    }
    public static void main(String[] args)throws Exception{
        if(args[0].equals("list")){
            for(String name:Files.readAllLines(Path.of(args[1]))){
                try{settings(name);System.out.println(name);}catch(IllegalArgumentException ignored){}
            }
            return;
        }
        try(var in=new BufferedReader(new InputStreamReader(System.in))){
            HistoricalMovement bot=null;int heads=0,keyErrors=0,sideErrors=0,clickErrors=0;String line;
            while((line=in.readLine())!=null){
                var v=line.split(" ");
                if(v[0].equals("@")){
                    if(bot!=null)System.out.printf("%d %d %d %d%n",heads,keyErrors,sideErrors,clickErrors);
                    bot=new HistoricalMovement(v[1],Integer.parseInt(v[2]),Integer.parseInt(v[3]));
                    heads=keyErrors=sideErrors=clickErrors=0;continue;
                }
                long[] t=new long[6];for(int i=0;i<t.length;i++)t[i]=Long.parseLong(v[i]);
                double[] d=new double[14];for(int i=0;i<d.length;i++)d[i]=Double.parseDouble(v[i+6]);
                var a=bot.decide(new Head(t[0],t[1],t[2],t[3],t[4],t[5],d[0],d[1],d[2],d[3],d[4],d[5],d[6],d[7],d[8]!=0,d[9],d[10],d[11],d[12],d[13]));
                int key=Integer.parseInt(v[20]),lateral=Integer.parseInt(v[21]),click=Integer.parseInt(v[22]);heads++;
                if(a.key()!=key)keyErrors++;
                if((int)Math.signum(-a.strafe())!=lateral)sideErrors++;
                if(a.click()!=(click!=0))clickErrors++;
            }
            if(bot!=null)System.out.printf("%d %d %d %d%n",heads,keyErrors,sideErrors,clickErrors);
        }
    }
}
