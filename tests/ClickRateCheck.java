import lab.ClickRate;
import java.util.Properties;

public final class ClickRateCheck {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Properties p=new Properties();
        check(ClickRate.cps(p)==10&&ClickRate.intervalNanos(p)==100_000_000L,"legacy ceiling unchanged");
        p.setProperty("click_cps","20");p.setProperty("click_min_interval_ms","51");
        check(ClickRate.intervalNanos(p)==51_000_000L,"explicit model pacing floor");
        check(!ClickRate.ready(151_000_000L-1,100_000_000L,p),"51ms boundary remains closed before floor");
        check(ClickRate.ready(151_000_000L,100_000_000L,p),"51ms boundary opens exactly");
        p.setProperty("click_min_interval_ms","1");
        check(ClickRate.intervalNanos(p)==50_000_000L,"pacing cannot weaken physical CPS ceiling");
        for(String bad:new String[]{"-1","NaN","Infinity","1001"}){
            p.setProperty("click_min_interval_ms",bad);boolean rejected=false;
            try{ClickRate.intervalNanos(p);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid pacing rejected");
        }
        p.remove("click_min_interval_ms");
        int[] rates={10,15,20,25,30};
        long[] periods={100_000_000L,66_666_667L,50_000_000L,40_000_000L,33_333_334L};
        for(int i=0;i<rates.length;i++){
            p.setProperty("click_cps",Integer.toString(rates[i]));long period=periods[i];
            check(ClickRate.intervalNanos(p)==period,"round up fractional nanoseconds");
            check(!ClickRate.ready(2_000_000_000L+period-1,2_000_000_000L,p),"cannot click early");
            check(ClickRate.ready(2_000_000_000L+period,2_000_000_000L,p),"boundary eligible");
            long now=2_000_000_000L,last=now;int count=0;
            for(int poll=0;poll<2000;poll++){
                now+=7_000_000L+poll%3*1_000_000L;
                if(poll==500)now+=700_000_000L;
                if(ClickRate.ready(now,last,p)){
                    check(now-last>=period,"irregular polling preserves input ceiling");
                    last=now+2_000_000L;count++;
                    check(!ClickRate.ready(last,last,p),"no catch-up after stalls or injection cost");
                }
            }
            check(count>100,"test exercised actual inputs");
        }
        for(String bad:new String[]{"0","31","-1","NaN","20.5"}){
            p.setProperty("click_cps",bad);boolean rejected=false;
            try{ClickRate.intervalNanos(p);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid setting rejected: "+bad);
        }
        System.out.println("PASS explicit click ceilings, irregular polling, injection cost and no catch-up");
    }
}
