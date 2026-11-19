package lab;

/** An explicitly programmed family of normal-key fighters. Search uses a short,
 * approximate local forecast. Tournament rollouts and final scoring use the full plant. */
public final class CombatTactics {
    public static final int PARAMETERS=16, PROTOTYPES=12, DIMENSIONS=6;
    public static final int SIZE=PARAMETERS+PROTOTYPES*(DIMENSIONS+1);
    public static final int WORDS=10, TABLE=10, SEARCH=11;
    public static final String[] NAMES={"pressure","rearm","w-tap","brake","space","select","jump-reset","orbit","counterstep","mid-trade","table","search"};
    private final float[] w;
    private int heads,remaining,word;
    private int sideBias=1;
    private double floor=Double.NaN,previousTargetY;
    private boolean hadIncoming,hadOutgoing;
    private final double[] situation=new double[DIMENSIONS];

    CombatTactics(float[] weights){w=weights;}
    private CombatTactics(CombatTactics s){
        w=s.w;heads=s.heads;remaining=s.remaining;word=s.word;sideBias=s.sideBias;
        floor=s.floor;previousTargetY=s.previousTargetY;hadIncoming=s.hadIncoming;hadOutgoing=s.hadOutgoing;
        System.arraycopy(s.situation,0,situation,0,DIMENSIONS);
    }
    CombatTactics copy(){return new CombatTactics(this);}
    public int word(){return word;}
    public static int index(double n,int max){return (int)Math.max(0,Math.min(max,Math.round(n)));}
    private static boolean reached(double gap,double myY,double targetY){
        double vertical=Math.max(0,Math.max(targetY-.1-(myY+1.62),(myY+1.62)-(targetY+1.9)));
        return Math.hypot(Math.max(0,Math.abs(gap)-.4),vertical)<=3;
    }

    static int nearest(float[] w,double[] x){
        double best=Double.POSITIVE_INFINITY;int chosen=0,offset=PARAMETERS;
        for(int i=0;i<PROTOTYPES;i++){
            double d=0;for(int j=0;j<DIMENSIONS;j++){double diff=x[j]-w[offset++];d+=diff*diff;}
            int action=index(w[offset++],WORDS-1);
            if(d<best){best=d;chosen=action;}
        }
        return chosen;
    }

    static int controls(int action,double gap,double v,double otherV,boolean ground,boolean sprint,
                        double ownAge,double targetAge,boolean edge,int centerSide,int bias,
                        int head,float[] w){
        boolean away=v < -w[5];
        boolean closing=v-otherV>.04;
        boolean window=targetAge>=w[3]-80;
        boolean active=gap<3.55;
        int key=2,side=0;boolean click=true,jump=false;
        switch(action){
            case 0:break;
            case 1:key=sprint?1:2;break;
            case 2:if(targetAge<w[4])key=0;break;
            case 3:
                key=away||window||!sprint?2:1;
                click=away||window||!(active&&v>w[5]&&closing);
                break;
            case 4:
                if(!away && gap<w[2] && closing)key=gap<w[2]-.45?3:0;
                else if(!window && sprint)key=1;
                break;
            case 5:
                if(!Double.isFinite(ownAge) && !Double.isFinite(targetAge) && gap<3.7 && head<w[10]){
                    key=0;click=false;
                }else if(ownAge<100 && !away && !window)key=1;
                break;
            case 6:jump=ground && ownAge<w[8] && (away||edge);key=sprint&&!away?1:2;break;
            case 7:side=gap<4.1?(edge&&centerSide!=0?centerSide:bias):0;key=sprint&&!away?1:2;break;
            case 8:
                if(edge){side=centerSide;if(away&&ground&&ownAge<w[8])jump=true;}
                else if(active && !window && closing){key=gap<w[2]-.25?3:0;}
                break;
            case 9:
                if(away || ownAge<w[4])key=2;
                else if(targetAge<w[4] && gap<w[2]+.15)key=0;
                else key=sprint?1:2;
                break;
            default:throw new IllegalArgumentException("Unknown input word");
        }
        return DuelNetwork.pack(key,side,click,jump);
    }

    /** Own frame is positive toward the opponent. Forecast two plausible return schedules. */
    private double forecast(int action,double gap,double myV,double targetV,double y,double targetY,double vy,double tvy,
                            boolean ground,boolean sprint,double ownAge,double targetAge,boolean edge,
                            int centerSide,int response,int horizon){
        double mine=0,other=gap,myStart=y,theirStart=targetY;
        boolean theirsGround=targetY<=floor+.02;
        double theirReady=Math.max(0,w[3]-ownAge)+(response==1?85:0);
        double myReady=Math.max(0,w[3]-targetAge);double score=0;
        for(int t=0;t<horizon;t++){
            double dist=other-mine,sign=dist>=0?1:-1;
            int a=controls(action,Math.abs(dist),myV*sign,targetV*sign,ground,sprint,ownAge+t*50,targetAge+t*50,
                           edge,centerSide,sideBias,heads+t,w);
            int k=DuelNetwork.key(a);boolean attack=DuelNetwork.click(a)&&reached(dist,y,targetY);
            boolean incoming=reached(dist,targetY,y),giving=attack&&myReady<=0,received=incoming&&theirReady<=0;
            boolean bonus=sprint;
            if(attack&&sprint){myV*=.6;sprint=false;}
            if(incoming && response==0)targetV*=.6;
            if(giving){targetV=sign*(w[13]+(bonus?.327:0));tvy=.361375;theirsGround=false;myReady=w[3];score+=w[12];}
            if(received){myV=-sign*(w[13]+(response==1?.327:0));vy=.361375;ground=false;theirReady=w[3];score-=w[11];}
            if(k==2)sprint=true;else if(k==0||k==3)sprint=false;
            if(DuelNetwork.jump(a)&&ground){vy=.42;if(sprint)myV+=sign*.2;ground=false;}
            int dir=k==0?0:k==3?-1:1;
            double accel=ground?(sprint?.13:.1):(sprint?.026:.02);
            double strafe=DuelNetwork.side(a)==0?1:.70710678;
            myV+=dir*sign*accel*strafe;targetV-=sign*(theirsGround?.12:.023);
            mine+=myV;other+=targetV;y+=vy;targetY+=tvy;
            myV*=ground?.546:.91;targetV*=theirsGround?.546:.91;
            if(y<=floor){y=floor;ground=true;vy=0;}else vy=(vy-.08)*.98;
            if(targetY<=floor){targetY=floor;theirsGround=true;tvy=0;}else tvy=(tvy-.08)*.98;
            if(edge && centerSide!=0 && DuelNetwork.side(a)==centerSide)score+=.03;
            theirReady-=50;myReady-=50;
        }
        score+=w[14]*mine+(other-gap);
        if(myReady<110 && !reached(other-mine,y,targetY))score-=w[9];
        if(edge && mine<0)score+=Math.min(0,mine)*w[7];
        if(!ground && myStart<=floor+.02 && theirStart<=floor+.02 && y>targetY+.4)score-=.06;
        return score;
    }

    int step(double[] base,double gap,double y,double ty,double vy,int groundKey,
             boolean sprint,double ownAge,double targetAge){
        boolean ground=groundKey!=0;
        if(Double.isNaN(floor)){floor=y;previousTargetY=ty;sideBias=w[1]>=0?1:-1;}
        double tvy=ty-previousTargetY;previousTargetY=ty;heads++;
        double v=base[2]/2.5,otherV=base[5]/2.5;
        boolean edge=base[14]>w[7],freshOwn=Double.isFinite(ownAge),freshOther=Double.isFinite(targetAge);
        int centerSide=Math.abs(base[13])>.04?(base[13]>0?1:-1):0;
        if(freshOwn&&!hadIncoming)sideBias=centerSide==0?-sideBias:centerSide;
        int kind=index(w[0],SEARCH);
        if(kind<WORDS)word=kind;
        else if(remaining<=0 || freshOwn!=hadIncoming || freshOther!=hadOutgoing || ownAge<50 || targetAge<50){
            situation[0]=base[0];situation[1]=base[2];situation[2]=base[1];
            situation[3]=base[10];situation[4]=base[11];situation[5]=base[14];
            if(kind==TABLE)word=nearest(w,situation);
            else{
                int horizon=2+index(w[6],4);double best=Double.NEGATIVE_INFINITY;int choice=0;
                for(int i=0;i<WORDS;i++){
                    double a=forecast(i,gap,v,otherV,y,ty,vy,tvy,ground,sprint,ownAge,targetAge,edge,centerSide,0,horizon);
                    double b=forecast(i,gap,v,otherV,y,ty,vy,tvy,ground,sprint,ownAge,targetAge,edge,centerSide,1,horizon);
                    double merit=(a+b)*.25+.5*Math.min(a,b);
                    // Prefer the incumbent word on a true forecast tie.
                    if(merit>best || merit==best&&i==word){best=merit;choice=i;}
                }
                word=choice;
            }
            remaining=1+index(w[15],4);
        }
        remaining--;hadIncoming=freshOwn;hadOutgoing=freshOther;
        return controls(word,gap,v,otherV,ground,sprint,ownAge,targetAge,edge,centerSide,sideBias,heads,w);
    }
}
