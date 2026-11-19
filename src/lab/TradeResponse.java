package lab;

import java.util.Arrays;

/** A fitted lookup of incremental three-head returns relative to continuing the base policy. */
public final class TradeResponse {
    public static final int[] INPUTS={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,20,21};
    public static final int WORDS=9, CENTERS=32, NODE=20+WORDS;
    public static final int HEADER=5, BASE_MAX=229, TABLE_START=HEADER+BASE_MAX;
    public static final int SIZE=TABLE_START+CENTERS*NODE;
    private final float[] w;
    private final DuelNetwork base;
    private final double[] gains=new double[WORDS];
    private boolean seen,inExchange;
    private int exchanges;
    private int remaining,choice;
    TradeResponse(float[] w){
        this.w=w;
        DuelNetwork.Family family=w[0]<.5?DuelNetwork.Family.TACTIC:DuelNetwork.Family.MLP;
        base=new DuelNetwork(family,Arrays.copyOfRange(w,HEADER,HEADER+DuelNetwork.size(family)));
    }
    private TradeResponse(TradeResponse s){
        w=s.w;base=s.base.copy();seen=s.seen;inExchange=s.inExchange;exchanges=s.exchanges;remaining=s.remaining;choice=s.choice;
        System.arraycopy(s.gains,0,gains,0,WORDS);
    }
    TradeResponse copy(){return new TradeResponse(this);}
    public int choice(){return choice;}
    public boolean triggered(){return seen;}
    double[] features(){return base.features();}
    public static int decide(float[] w,double[] x,double[] gains){
        int count=Math.max(1,Math.min(CENTERS,Math.round(w[1])));
        double bandwidth=Math.max(.025,w[2]),total=0;Arrays.fill(gains,0);
        for(int i=0;i<count;i++){
            int j=TABLE_START+i*NODE;double d=0;
            for(int feature:INPUTS){double delta=x[feature]-w[j++];d+=delta*delta;}
            double mass=1/(bandwidth+d);mass=mass*mass*mass;
            for(int a=0;a<WORDS;a++)gains[a]+=mass*w[j++];
            total+=mass;
        }
        int best=0;for(int a=1;a<WORDS;a++)if(gains[a]>gains[best])best=a;
        return gains[best]>Math.max(0,w[3])*total?best:0;
    }
    int step(double x,double y,double z,double tx,double ty,double tz,double vx,double vy,double vz,
             boolean ground,boolean sprint,double ownAge,double targetAge,double rtt){
        int a=base.step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,rtt);
        if(remaining>0 && choice>0){
            int key=choice==1?1:choice==2?0:choice==3?3:2,side=choice==7?1:choice==8?-1:0;
            double dist=Math.hypot(tx-x,tz-z);
            boolean away=dist>0 && (vx*(tx-x)+vz*(tz-z))/dist<0;
            remaining--;
            return DuelNetwork.pack(key,side,choice==4?away:choice!=5||remaining==0,choice==6&&remaining==2&&ground);
        }
        boolean paired=ownAge<230 && targetAge<230;
        if(paired && !inExchange)exchanges++;
        inExchange=paired;
        if(!seen && paired && exchanges>=(w[4]>=.5f?2:1)){
            seen=true;choice=decide(w,base.features(),gains);remaining=3;
        }
        return a;
    }
    void applied(int a){base.applied(a);}
}
