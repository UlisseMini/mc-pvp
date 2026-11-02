package lab;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exact H2 own-observation admission rule, with immutable explicit-row bypass.
 * Additional permission only: never injects, cancels input, or changes the cap.
 * All times must use the same local monotonic millisecond origin. Java 8.
 */
public final class SeedPlanAdmission {
    private final boolean[] bypass;
    private final double halfRtt;
    private final int hitTicks;

    public SeedPlanAdmission(Path path,double ownRtt,int hitTicks)throws IOException {
        if(!Double.isFinite(ownRtt)||ownRtt<0||hitTicks<1||hitTicks>20)
            throw new IllegalArgumentException("Finite RTT and hit ticks 1..20 required");
        halfRtt=ownRtt/2;this.hitTicks=hitTicks;
        List<Boolean> rows=new ArrayList<Boolean>();
        try(BufferedReader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            String line;int number=0;
            while((line=reader.readLine())!=null){
                number++;line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;
                if(rows.isEmpty()&&line.equals("index\tbypass_own_admission"))continue;
                String[] q=line.split("\\s+");
                if(q.length!=2)throw new IllegalArgumentException("Admission mask line "+number+": expected two columns");
                int index,value;
                try{index=Integer.parseInt(q[0]);value=Integer.parseInt(q[1]);}
                catch(NumberFormatException e){throw new IllegalArgumentException("Admission mask line "+number+": integer required",e);}
                if(index!=rows.size()||index>=4096||(value!=0&&value!=1))
                    throw new IllegalArgumentException("Admission mask line "+number+": contiguous indices and 0/1 values required");
                rows.add(value==1);
            }
        }
        if(rows.isEmpty())throw new IllegalArgumentException("Empty admission mask");
        bypass=new boolean[rows.size()];for(int i=0;i<bypass.length;i++)bypass[i]=rows.get(i);
    }
    public int size(){return bypass.length;}
    public boolean bypasses(int index){return index>=0&&index<bypass.length&&bypass[index];}
    public boolean allow(int wordIndex,boolean nativeTickPoll,double pollMs,double lastHeadMs,double handledTargetHurtMs){
        if(wordIndex<0||wordIndex>=bypass.length)return false;
        if(!Double.isFinite(pollMs)||!Double.isFinite(lastHeadMs))throw new IllegalArgumentException("Finite local poll/HEAD clocks required");
        if(bypass[wordIndex]||!Double.isFinite(handledTargetHurtMs))return true;
        double consume=nativeTickPoll?pollMs:Math.max(pollMs,lastHeadMs+50);
        double latestDrain=consume+halfRtt+5+50;
        double readyLow=handledTargetHurtMs-halfRtt+hitTicks*50-5;
        return latestDrain>=readyLow;
    }
}
