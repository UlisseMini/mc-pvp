package lab;

import java.nio.ByteBuffer;
import java.util.Base64;

/** Java 8 inference shared by the Forge player and tournament. One instance per fighter per bout. */
public final class DuelNetwork {
    public enum Family { LINEAR, MLP, RECURRENT, HYBRID, IMITATION, ROUTER, TACTIC, RESPONSE, PAIR, PING_MLP, INPUT8, INPUT32 }
    public static final int INPUTS=22, HIDDEN=8, OUTPUTS=5;
    public static final int STRIDE=12;
    private final Family family;
    private HumanTiming imitation;
    private ExpertRouter router;
    private CombatTactics tactics;
    private TradeResponse response;
    private PairedExperts pair;
    private final float[] weights;
    private final double[] features, hidden, next;
    private final double[] outputs=new double[OUTPUTS];
    private boolean started;
    private double centerX,centerZ,previousTargetX,previousTargetZ,previousTargetY;
    private int previousAction;

    private SumoInputs extra;
    private double decisionTime,pressAge=Double.POSITIVE_INFINITY;
    public void localTiming(double time,double sincePress){decisionTime=time;pressAge=sincePress;}
    public static boolean study(Family f){return f==Family.INPUT8||f==Family.INPUT32;}
    public static int width(Family f){return f==Family.INPUT32?32:HIDDEN;}
    public static int size(Family f) {
        if(study(f))return width(f)*(SumoInputs.COUNT+1)+OUTPUTS*(width(f)+1)+1;
        if(f==Family.PAIR)return PairedExperts.SIZE;
        if(f==Family.RESPONSE)return TradeResponse.SIZE;
        if(f==Family.TACTIC)return CombatTactics.SIZE;
        if(f==Family.ROUTER)return ExpertRouter.SIZE;
        if(f==Family.IMITATION)return HumanTiming.SIZE;
        if(f==Family.LINEAR)return OUTPUTS*(INPUTS+1);
        int recurrent=(f==Family.RECURRENT || f==Family.HYBRID)?HIDDEN:0;
        return HIDDEN*(INPUTS+1+(f==Family.PING_MLP?2:0)+recurrent)+OUTPUTS*(HIDDEN+1);
    }

    /** Treat weights as immutable; model search builds a new array for every child. */
    public DuelNetwork(Family family,float[] weights) {
        if(weights.length!=size(family))throw new IllegalArgumentException("Wrong network dimensions");
        this.family=family;this.weights=weights;features=new double[study(family)?SumoInputs.COUNT:family==Family.PING_MLP?INPUTS+2:INPUTS];
        hidden=new double[width(family)];next=new double[hidden.length];
        if(study(family)){
            float mask=weights[weights.length-1];
            if(mask!=(int)mask || mask<0 || mask>127)throw new IllegalArgumentException("Unknown study feature mask");
            extra=new SumoInputs((int)mask);
        }
        if(family==Family.PAIR)pair=new PairedExperts(weights);
        if(family==Family.RESPONSE)response=new TradeResponse(weights);
        if(family==Family.TACTIC)tactics=new CombatTactics(weights);
        if(family==Family.ROUTER)router=new ExpertRouter(weights);
        if(family==Family.IMITATION)imitation=new HumanTiming(weights);
    }
    public DuelNetwork copy(){
        DuelNetwork c=new DuelNetwork(family,weights);
        if(pair!=null)c.pair=pair.copy();
        if(response!=null)c.response=response.copy();
        if(tactics!=null)c.tactics=tactics.copy();
        if(router!=null)c.router=router.copy();
        if(imitation!=null)c.imitation=imitation.copy();
        c.started=started;c.centerX=centerX;c.centerZ=centerZ;c.previousTargetX=previousTargetX;
        c.previousTargetY=previousTargetY;c.previousTargetZ=previousTargetZ;c.previousAction=previousAction;
        System.arraycopy(features,0,c.features,0,features.length);
        System.arraycopy(hidden,0,c.hidden,0,hidden.length);System.arraycopy(next,0,c.next,0,next.length);
        if(extra!=null)c.extra=extra.copy();c.decisionTime=decisionTime;c.pressAge=pressAge;
        System.arraycopy(outputs,0,c.outputs,0,OUTPUTS);
        return c;
    }
    public static String encode(Family f,float[] weights){
        ByteBuffer bytes=ByteBuffer.allocate(weights.length*4);
        for(float w:weights)bytes.putFloat(w);
        return f.name()+"."+Base64.getEncoder().encodeToString(bytes.array());
    }
    public static DuelNetwork decode(String encoded){
        int split=encoded.indexOf('.');
        if(split<0)throw new IllegalArgumentException("Missing duel network family");
        Family f=Family.valueOf(encoded.substring(0,split));
        ByteBuffer bytes=ByteBuffer.wrap(Base64.getDecoder().decode(encoded.substring(split+1)));
        float[] w=new float[size(f)];
        if(bytes.remaining()!=4*w.length)throw new IllegalArgumentException("Wrong duel network byte count");
        for(int i=0;i<w.length;i++) {
            w[i]=bytes.getFloat();
            if(!Float.isFinite(w[i]))throw new IllegalArgumentException("Non-finite duel weight");
        }
        return new DuelNetwork(f,w);
    }
    public float[] weights(){return weights.clone();}
    public Family family(){return family;}
    public double[] features(){return response==null?features.clone():response.features();}
    public double[] scores(){return outputs.clone();}
    public TradeResponse response(){return response;}
    public CombatTactics tactics(){return tactics;}
    public PairedExperts pair(){return pair;}
    public ExpertRouter router(){return router;}
    public double[] imitationInputs(){return imitation.inputs();}
    public void applied(int action){previousAction=action;
        if(pair!=null)pair.applied(action);
        if(imitation!=null)imitation.applied(action);
        if(router!=null)router.applied(action);
        if(response!=null)response.applied(action); }

    private static double clip(double v){return Math.max(-1,Math.min(1,v));}
    public static int pack(int key,int side,boolean click,boolean jump){
        return key+4*(side+1)+(click?STRIDE:0)+(jump?2*STRIDE:0);
    }
    public static int key(int a){return a%4;} // 0 release, 1 W, 2 W+Ctrl, 3 S
    public static int side(int a){return a/4%3-1;} // +1 = left
    public static boolean click(int a){return (a/STRIDE&1)!=0;}
    public static boolean jump(int a){return a>=2*STRIDE;}
    private static double freshness(double age){return clip(1-age/300);}

    /** Ages are elapsed local milliseconds since this client's hurt-status handlers. */
    public int step(double x,double y,double z,double tx,double ty,double tz,
                    double vx,double vy,double vz,boolean grounded,boolean sprint,
                    double ownHurtAge,double targetHurtAge,double rtt){
        return step(x,y,z,tx,ty,tz,vx,vy,vz,grounded,sprint,ownHurtAge,targetHurtAge,rtt,Double.NaN);
    }
    public int step(double x,double y,double z,double tx,double ty,double tz,
                    double vx,double vy,double vz,boolean grounded,boolean sprint,
                    double ownHurtAge,double targetHurtAge,double rtt,double opponentRtt){
        if(family==Family.PING_MLP){
            boolean known=Double.isFinite(opponentRtt)&&opponentRtt>=0;
            features[22]=known?clip(opponentRtt/80-1):0;
            features[23]=known?1:-1;
        }
        if(response!=null)return response.step(x,y,z,tx,ty,tz,vx,vy,vz,grounded,sprint,ownHurtAge,targetHurtAge,rtt);
        if(router!=null)return router.step(x,y,z,tx,ty,tz,vx,vy,vz,grounded,sprint,ownHurtAge,targetHurtAge,rtt);
        if(!started){
            started=true;centerX=(x+tx)*.5;centerZ=(z+tz)*.5;
            previousTargetX=tx;previousTargetZ=tz;previousTargetY=ty;
            previousAction=pack(0,0,false,false);
        }
        double dx=tx-x,dz=tz-z,dist=Math.hypot(dx,dz),length=Math.max(1e-8,dist);
        double fx=dx/length,fz=dz/length;
        double targetDx=tx-previousTargetX,targetDz=tz-previousTargetZ;
        features[0]=clip((dist-3)/2);features[1]=clip(ty-y);
        features[2]=clip((vx*fx+vz*fz)*2.5);features[3]=clip((vx*fz-vz*fx)*2.5);
        features[4]=clip(vy*2);features[5]=clip((targetDx*fx+targetDz*fz)*2.5);
        features[6]=clip((targetDx*fz-targetDz*fx)*2.5);features[7]=clip((ty-previousTargetY)*2);
        features[8]=grounded?1:-1;features[9]=sprint?1:-1;
        features[10]=freshness(ownHurtAge);features[11]=freshness(targetHurtAge);
        features[12]=clip(((centerX-x)*fx+(centerZ-z)*fz)/5);
        features[13]=clip(((centerX-x)*fz-(centerZ-z)*fx)/5);
        features[14]=clip(Math.hypot(x-centerX,z-centerZ)/2.5-1);
        features[15]=clip(rtt/80-1);
        int k=key(previousAction);
        features[16]=k==3?-1:k==0?0:1;features[17]=side(previousAction);
        features[18]=click(previousAction)?1:-1;features[19]=jump(previousAction)?1:-1;
        features[20]=clip((dist-3.3)*4);features[21]=clip((targetHurtAge-ownHurtAge)/300);
        if(Double.isNaN(features[21]))features[21]=0; // neither player has shown a hurt cue
        if(extra!=null)extra.fill(features,x,y,z,tx,ty,tz,vx,vy,vz,targetDx,ty-previousTargetY,targetDz,fx,fz,centerX,centerZ,decisionTime,pressAge);
        previousTargetX=tx;previousTargetY=ty;previousTargetZ=tz;
        if(pair!=null)return pair.step(features,x,y,z,tx,ty,tz,vx,vy,vz,grounded,sprint,ownHurtAge,targetHurtAge,rtt);
        if(tactics!=null)return tactics.step(features,dist,y,ty,vy,grounded?1:0,sprint,ownHurtAge,targetHurtAge);
        if(imitation!=null)return imitation.step(features,grounded);
        boolean recurring=family==Family.RECURRENT||family==Family.HYBRID;
        int index=0;
        if(family!=Family.LINEAR){
            for(int h=0;h<hidden.length;h++){
                double v=weights[index++];
                for(double input:features)v+=weights[index++]*input;
                if(recurring)for(double memory:hidden)v+=weights[index++]*memory;
                // A bounded softsign unit keeps the same arithmetic in Java 8 and 17.
                next[h]=v/(1+Math.abs(v));
            }
            System.arraycopy(next,0,hidden,0,hidden.length);
        }
        double[] inputs=family==Family.LINEAR?features:hidden;
        for(int i=0;i<OUTPUTS;i++){
            double v=weights[index++];
            for(double input:inputs)v+=weights[index++]*input;
            outputs[i]=v;
        }
        // Hybrid learns residuals around pressure except just after an outgoing untraded hurt cue.
        if(family==Family.HYBRID){
            outputs[0]+=targetHurtAge<65 && ownHurtAge>230 ? -.7 : .7;
            outputs[1]+=.65;outputs[2]+=.7;outputs[3]-=.5;
        }
        int key=outputs[0]<-.65?3:outputs[0]>0?(outputs[1]>0?2:1):0;
        int side=outputs[4]>.45?1:outputs[4]<-.45?-1:0;
        return pack(key,side,outputs[2]>0,grounded&&outputs[3]>0);
    }
}
