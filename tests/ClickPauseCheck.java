import java.util.Arrays;
import lab.ClickPause;

public final class ClickPauseCheck {
    static double[] head(ClickPause m,long t,long landed,long hurt,boolean sprint){
        return m.observe(t+100000,t,landed,hurt,2.8,2.4,4.7,4,.1,sprint,80);
    }
    static void require(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        ClickPause memory=new ClickPause();
        head(memory,100,100,0,true);head(memory,550,550,0,true);head(memory,1000,1000,0,true);
        require(head(memory,2000,1000,0,true)==null);
        head(memory,2100,2100,0,true);head(memory,2550,2550,0,true);head(memory,3000,3000,0,true);
        require(head(memory,3050,3000,0,true)==null);
        ClickPause copy=new ClickPause(memory);
        double[] x=head(memory,3100,3000,0,true);require(x!=null&&Arrays.equals(x,head(copy,3100,3000,0,true)));
        require(head(memory,3150,3000,0,true)==null);
        memory.pause(80);for(int tick=80;tick<84;tick++)require(!memory.allows(tick));
        require(memory.allows(84)&&copy.allows(80));
        head(memory,3200,3000,3200,true);head(memory,3300,3300,3200,true);
        require(head(memory,3400,3300,3200,true)==null);
        ClickPause.Tree tree=new ClickPause.Tree("0,2.1,1,2|keep|0,2.55,3,4|pause|keep");
        for(double d:new double[]{2.1,2.10001,2.55,2.55001}){x[0]=d;require(tree.pause(x)==(d>2.1&&d<=2.55));}
        System.out.println("PASS paired observation histories, pre-wake cues, four-head pause, return reset and tree boundaries");
    }
}
