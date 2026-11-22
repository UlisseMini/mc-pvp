package lab;

/** Shared, piecewise-linear game time. Transitions have a common loopback-host wall timestamp. */
public final class TrainingClock {
    public static final boolean ROOM = Boolean.getBoolean("lab.trainingRoom");
    static final long EPOCH = Long.getLong("lab.trainingEpoch", 0L);
    private static final double INITIAL = Double.parseDouble(System.getProperty("lab.trainingSpeed", "1"));
    private static final long MONO_EPOCH = ROOM ? System.nanoTime()-(System.currentTimeMillis()-EPOCH)*1_000_000L : 0;
    private static volatile long wallEpochOnMonotonicClock = MONO_EPOCH;
    static final class Segment {
        final long wall, gameNs;
        final double rate;
        final Segment prior;
        Segment(long wall,long gameNs,double rate,Segment prior){
            if(!valid(rate))throw new IllegalArgumentException("Speed must be between 0.10 and 1.00");
            this.wall=wall;this.gameNs=gameNs;this.rate=rate;this.prior=prior;
        }
        long game(long wallMs){return gameNs+Math.round((wallMs-wall)*1_000_000.0*rate);}
    }
    private static volatile Segment latest=new Segment(EPOCH,0,INITIAL,null);
    static {
        if(ROOM && EPOCH<=0)throw new IllegalArgumentException("Missing training clock epoch");
        if(ROOM && System.getProperty("lab.trainingWall")!=null)
            synchronize(Long.getLong("lab.trainingWall"),Long.getLong("lab.trainingGameNs"),INITIAL,
                        Long.getLong("lab.trainingNextWall"),Double.parseDouble(System.getProperty("lab.trainingNextSpeed")));
    }
    private TrainingClock() {}
    static boolean valid(double rate){return Double.isFinite(rate)&&rate>=.1&&rate<=1;}
    static Segment last(){return latest;}
    static Segment at(long wall){
        Segment s=latest;
        while(s.prior!=null && wall<s.wall)s=s.prior;
        return s;
    }
    static synchronized void schedule(long wall,double rate){
        Segment current=at(System.currentTimeMillis());
        latest=new Segment(wall,current.game(wall),rate,current);
    }
    /** Install the server's exact game-time anchor, including when a bot joins late. */
    public static synchronized void synchronize(long wall,long ns,double rate,long nextWall,double nextRate){
        if(!ROOM||wall<EPOCH||ns<0||!valid(rate)||(nextWall!=0&&(nextWall<=wall||!valid(nextRate))))return;
        Segment s=latest;
        if(s.wall==nextWall&&s.rate==nextRate&&s.prior!=null)s=s.prior;
        if(s.wall!=wall||s.gameNs!=ns||s.rate!=rate){
            while(s.prior!=null&&s.wall>=wall)s=s.prior;
            s=new Segment(wall,ns,rate,s.wall<wall?s:null);
        }
        if(nextWall==0)latest=s;
        else if(latest.wall!=nextWall||latest.rate!=nextRate||latest.prior!=s)
            latest=new Segment(nextWall,s.game(nextWall),nextRate,s);
    }
    public static double rate(){return ROOM?at(System.currentTimeMillis()).rate:1;}
    public static long gameMillis(long wall){return ROOM?EPOCH+Math.round(at(wall).game(wall)/1e6):wall;}
    public static long millis(){return gameMillis(System.currentTimeMillis());}
    public static long nanos(){return gameNanos(System.nanoTime());}
    public static long gameNanos(long now){
        if(!ROOM)return now;
        return gameNanos(now,System.nanoTime(),System.currentTimeMillis());
    }
    static long gameNanos(long eventNs,long sampledNs,long sampledWallMs){
        // nanoTime and wall time can advance by different amounts across host sleep.
        // Keep nanosecond precision between samples, but select a rate at the actual
        // wall instant of the event, including a recent event before a speed change.
        long origin=wallEpochOnMonotonicClock;
        long sampledOffset=(sampledWallMs-EPOCH)*1_000_000L;
        if(Math.abs((sampledNs-origin)-sampledOffset)>2_000_000L){
            origin=sampledNs-sampledOffset;
            wallEpochOnMonotonicClock=origin;
        }
        long offset=eventNs-origin;
        Segment s=at(EPOCH+Math.floorDiv(offset,1_000_000L));
        return MONO_EPOCH+s.gameNs+Math.round((offset-(s.wall-EPOCH)*1_000_000L)*s.rate);
    }
    public static long wallTimestamp(long gameMs){
        if(!ROOM)return gameMs;
        long ns=(gameMs-EPOCH)*1_000_000L;
        Segment s=latest;
        while(s.prior!=null&&ns<s.gameNs)s=s.prior;
        return s.wall+Math.round((ns-s.gameNs)/(1e6*s.rate));
    }
    public static long wallDuration(long gameMs){
        long wall=System.currentTimeMillis();
        return ROOM?wallTimestamp(gameMillis(wall)+gameMs)-wall:gameMs;
    }
    public static void sleep(long gameMs)throws InterruptedException{Thread.sleep(Math.max(0,wallDuration(gameMs)));}
}
