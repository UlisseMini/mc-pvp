package lab;

/** Forward pass and physical-button sampler shared by the native bot and the league workers. */
public final class SumoLeaguePolicy {
    public static final int INPUTS=25, HEADS=6;
    public static final double TEMPERATURE=.25;
    private final float[] w;
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
        recurrent=gru;w=weights;this.width=width;
        h=new double[width];gates=new double[3*width];history=new double[3*width];
    }
    SumoLeaguePolicy copy(){
        SumoLeaguePolicy c=new SumoLeaguePolicy(recurrent,width,w);
        System.arraycopy(h,0,c.h,0,width);c.randomState=randomState;c.value=value;return c;
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
}
