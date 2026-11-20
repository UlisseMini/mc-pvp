package lab;

/** Forward pass and physical-button sampler shared by the native bot and the league workers. */
public class SumoLeaguePolicy {
    public static final int INPUTS=25, HEADS=6;
    public static final double TEMPERATURE=.25;
    private final float[] w;
    private final float[] recurrentBias;
    private final boolean recurrent;
    private final int width;
    private final double[] h, gates, history;
    private long randomState;
    private double value;

    public static int size(boolean gru){return size(gru,gru?32:180);}
    public static int size(boolean gru,int width){
        return (gru?3*width*(INPUTS+width+1):width*(INPUTS+1))+HEADS*(width+1);
    }
    SumoLeaguePolicy(boolean gru,float[] weights){this(gru,gru?32:180,weights);}
    SumoLeaguePolicy(boolean gru,int width,float[] weights){
        this(gru,width,weights,null);
    }
    private SumoLeaguePolicy(boolean gru,int width,float[] weights,float[] recurrentBias){
        recurrent=gru;w=weights;this.width=width;
        this.recurrentBias=recurrentBias;
        h=new double[width];gates=new double[3*width];history=new double[3*width];
    }
    SumoLeaguePolicy copy(){
        return new SumoLeaguePolicy(this);
    }
    private SumoLeaguePolicy(SumoLeaguePolicy other){
        this(other.recurrent,other.width,other.w,other.recurrentBias);
        System.arraycopy(other.h,0,h,0,width);randomState=other.randomState;value=other.value;
    }
    public void seed(long seed){randomState=seed;}
    public long randomState(){return randomState;}
    public double value(){return value;}
    public double[] memory(){return h.clone();}
    public void memory(double[] other){
        if(!recurrent||other.length!=width)throw new IllegalArgumentException("Expected GRU memory");
        System.arraycopy(other,0,h,0,width);
    }
    private static double sigmoid(double x){return 1/(1+Math.exp(-x));}
    private double uniform(){
        long z=(randomState+=0x9e3779b97f4a7c15L);
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;
        return ((z^(z>>>31))>>>11)*0x1.0p-53;
    }
    private double dot(int start,double[] inputs){
        double sum=0;for(int i=0;i<inputs.length;i++)sum+=w[start+i]*inputs[i];return sum;
    }
    public void forward(double[] x,double[] scores){
        int end;
        if(recurrent){
            int n=3*width,hh=n*INPUTS,bias=hh+n*width;
            for(int i=0;i<n;i++){
                gates[i]=dot(i*INPUTS,x)+w[bias+i];history[i]=dot(hh+i*width,h);
                if(recurrentBias!=null)history[i]+=recurrentBias[i];
            }
            for(int i=0;i<width;i++){
                double r=sigmoid(gates[i]+history[i]);
                double z=sigmoid(gates[width+i]+history[width+i]);
                double nval=Math.tanh(gates[2*width+i]+r*history[2*width+i]);
                h[i]=z*h[i]+(1-z)*nval;
            }
            end=bias+n;
        }else{
            int bias=width*INPUTS;
            for(int i=0;i<width;i++){double v=dot(i*INPUTS,x)+w[bias+i];h[i]=v/(1+Math.abs(v));}
            end=bias+width;
        }
        for(int k=0;k<HEADS;k++){
            double y=dot(end+k*width,h)+w[end+HEADS*width+k];
            if(k==5)value=y;else scores[k]=y;
        }
    }
    private int ordinal(double x,double low,double high){
        double u=uniform();
        return u<sigmoid((low-x)/TEMPERATURE)?-1:u>sigmoid((high-x)/TEMPERATURE)?1:0;
    }
    int decide(double[] x,double[] scores,boolean ground){
        forward(x,scores);
        int forward=ordinal(scores[0],-.65,0);
        int key=forward<0?3:forward==0?0:uniform()<sigmoid(scores[1]/TEMPERATURE)?2:1;
        int side=ordinal(scores[4],-.45,.45);
        boolean click=uniform()<sigmoid(scores[2]/TEMPERATURE);
        return DuelNetwork.pack(key,side,click,ground&&uniform()<sigmoid(scores[3]/TEMPERATURE));
    }

    /** Observe the same opening for 24 input heads. A trained two-class GRU chooses the continuation. */
    static final class Adapted extends SumoLeaguePolicy {
        static final int EXPERT=size(false), CLASSIFIER=size(true), BIAS=96;
        static final int SIZE=3*EXPERT+CLASSIFIER+BIAS+1;
        private final SumoLeaguePolicy first,second,identifier;
        private final double[] scratch=new double[HEADS-1];
        private final int mode;
        private int heads,chosen;
        Adapted(float[] packed){
            super(false,180,slice(packed,0,EXPERT));
            first=new SumoLeaguePolicy(false,slice(packed,EXPERT,EXPERT));
            second=new SumoLeaguePolicy(false,slice(packed,2*EXPERT,EXPERT));
            identifier=new SumoLeaguePolicy(true,32,slice(packed,3*EXPERT,CLASSIFIER),slice(packed,3*EXPERT+CLASSIFIER,BIAS));
            float tag=packed[SIZE-1];mode=(int)tag;
            if(tag!=mode||mode<0||mode>4)throw new IllegalArgumentException("Unknown history test mode");
        }
        private static float[] slice(float[] a,int offset,int n){return java.util.Arrays.copyOfRange(a,offset,offset+n);}
        private Adapted(Adapted other){
            super(other);
            first=other.first.copy();second=other.second.copy();identifier=other.identifier.copy();
            mode=other.mode;heads=other.heads;chosen=other.chosen;
        }
        @Override SumoLeaguePolicy copy(){return new Adapted(this);}
        @Override public void seed(long s){super.seed(s);first.seed(s);second.seed(s);}
        @Override public double value(){return heads<=24?super.value():selected().value();}
        @Override public long randomState(){return heads<=24?super.randomState():selected().randomState();}
        @Override public double[] memory(){return identifier.memory();}
        @Override public void memory(double[] state){identifier.memory(state);}
        private SumoLeaguePolicy selected(){return chosen==0?first:second;}
        @Override int decide(double[] x,double[] scores,boolean ground){
            heads++;
            if(heads>24)return selected().decide(x,scores,ground);
            first.decide(x,scratch,ground);second.decide(x,scratch,ground);
            if(mode==3&&heads==19)identifier.memory(new double[32]);
            identifier.forward(x,scratch);
            if(heads==24){
                chosen=scratch[1]>scratch[0]?1:0;
                if(mode==1||mode==2)chosen=mode-1;
                if(mode==4)chosen=1-chosen;
            }
            return super.decide(x,scores,ground);
        }
    }
}
