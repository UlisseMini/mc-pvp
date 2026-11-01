package practice.tests;
import practice.core.AntiAfk;
import java.util.Random;
public final class AntiAfkChecks {
    private static int checks;
    private static void ok(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static int run(){
        Object c=new Object();AntiAfk a=new AntiAfk(new Random(42));
        for(int i=0;i<1000;i++)ok(a.tick(i*1_000_000_000L,c,true,false,false)==0,"default OFF");
        a.enable(0,c,"30-90");ok(a.tick(29_000_000_000L,c,true,false,false)==0,"range minimum");
        boolean seen=false;for(long t=30_000_000_000L;t<=91_000_000_000L;t+=50_000_000L)if(a.tick(t,c,true,false,false)!=0){seen=true;break;}ok(seen,"range maximum");
        a.enable(0,c,"10");ok(a.tick(1_000_000_000L,c,false,false,false)==0&&a.enabled(),"same socket missing world preserves ON");
        ok(a.tick(2_000_000_000L,c,true,false,false)==0&&a.enabled(),"world return waits");
        a.tick(3_000_000_000L,null,false,false,false);ok(!a.enabled(),"actual disconnect OFF");
        a.enable(0,c,"10");a.tick(1,c,true,false,false);a.tick(2,new Object(),true,false,false);ok(!a.enabled(),"new socket OFF");
        a.enable(0,c,"10");for(long t=0;t<=60_000_000_000L;t+=50_000_000L)ok(a.tick(t,c,true,true,false)==0&&a.enabled(),"GUI pauses even long term");
        ok(a.tick(60_050_000_000L,c,true,false,false)==0,"no immediate GUI close pulse");
        seen=false;for(long t=60_100_000_000L;t<=71_000_000_000L;t+=50_000_000L)if(a.tick(t,c,true,false,false)!=0){seen=true;break;}ok(seen,"GUI freezes countdown instead of resetting each tick");
        int directions=0,sneaks=0;
        for(int seed=0;seed<100;seed++){
            a=new AntiAfk(new Random(seed));a.enable(0,c,"10");int[] counts=new int[4];double yaw=0,pitch=0;int active=0;
            for(int tick=0;tick<12;tick++){
                int keys=a.tick(10_000_000_000L+tick*50_000_000L,c,true,false,false);
                for(int k=0;k<4;k++)if((keys&(1<<k))!=0){counts[k]++;directions|=1<<k;active++;}
                if((keys&16)!=0)sneaks++;yaw+=a.yawDelta();pitch+=a.pitchDelta();
                ok(Math.abs(a.yawDelta())<=2.01&&Math.abs(a.pitchDelta())<=.61,"small gradual view delta");
            }
            ok(counts[0]==counts[1]&&counts[2]==counts[3],"balanced opposite ordinary inputs");
            int tap=Math.max(counts[0],counts[2]);ok(tap>=1&&tap<=4&&active==2*tap,"1–4 tick taps");
            ok(Math.abs(yaw)<1e-12&&Math.abs(pitch)<1e-12,"smooth drift returns to original view");
            a.enable(0,c,"10");a.tick(10_000_000_000L,c,true,false,false);ok(a.tick(10_050_000_000L,c,true,false,true)==0&&a.yawDelta()==0,"human input aborts keys and view immediately");
        }
        ok(directions==15&&sneaks>0,"random WASD and occasional sneak covered");
        AntiAfk.Ownership ownership=new AntiAfk.Ownership();
        for(int bit=1;bit<=16;bit<<=1){ok(ownership.applyMask(bit,0)[0]==bit,"owns only requested key");ok(ownership.applyMask(0,bit)[1]==0,"never clears human takeover");ownership.applyMask(bit,0);ok(ownership.applyMask(0,0)[1]==bit,"clears owned key on abort");ok(ownership.applyMask(0,0)[1]==0,"no unowned release");}
        for(String bad:new String[]{"9","3601","90-30","0-20","30-4000","x","30-","1-2-3","2147483648"}){boolean rejected=false;try{a.enable(0,c,bad);}catch(IllegalArgumentException expected){rejected=true;}ok(rejected,"reject invalid interval "+bad);}
        ok(!new AntiAfk().enabled(),"never restored on restart");
        System.out.println("AntiAfk checks passed: "+checks);return checks;
    }
}
