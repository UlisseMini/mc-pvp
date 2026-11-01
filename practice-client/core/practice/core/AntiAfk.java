package practice.core;

/** Explicit session-only normal input pulses. No network, position or motion writes. */
public final class AntiAfk {
    private boolean enabled;private Object connection;private long next;private int stage;
    private int intervalSeconds=60;
    public void enable(long now,Object connection,int seconds){
        if(connection==null)throw new IllegalArgumentException("Join a server first");
        if(seconds<10||seconds>3600)throw new IllegalArgumentException("Interval must be 10–3600 seconds");
        enabled=true;this.connection=connection;intervalSeconds=seconds;next=now+seconds*1_000_000_000L;stage=0;
    }
    public void disable(){enabled=false;connection=null;stage=0;}
    /** 1=forward, -1=back, 0=release owned synthetic keys. Pauses reset the
     * interval, so closing chat never causes a surprise immediate pulse. */
    public int tick(long now,Object actual,boolean playing,boolean gui,boolean humanMovement){
        if(!enabled)return 0;
        if(actual!=connection||actual==null||!playing){disable();return 0;}
        if(gui||humanMovement){stage=0;next=now+intervalSeconds*1_000_000_000L;return 0;}
        if(stage==0&&now<next)return 0;
        int direction=stage<3?1:-1;stage++;
        if(stage==6){stage=0;next=now+intervalSeconds*1_000_000_000L;}
        return direction;
    }
    /** Pure key ownership calculation; release only our synthetic press and
     * never clear a key which the human now physically holds. Bit1=W, bit2=S. */
    public static final class Ownership {
        private boolean forward,back;
        public int[] apply(int direction,boolean humanForward,boolean humanBack){
            int press=0,release=0;
            if(forward&&direction!=1){if(!humanForward)release|=1;forward=false;}
            if(back&&direction!=-1){if(!humanBack)release|=2;back=false;}
            if(direction==1){press|=1;forward=true;}
            if(direction==-1){press|=2;back=true;}
            return new int[]{press,release};
        }
    }
    public boolean enabled(){return enabled;}
    public String status(){return enabled?"Anti-AFK ON ("+intervalSeconds+"s, this connection only)":"Anti-AFK OFF";}
}
