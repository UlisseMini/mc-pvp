package lab;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read the same seat-ordered RTT pair a spectator or participant sees on the lab HUD. */
public final class DuelHudPing {
    private static final Pattern PAIR=Pattern.compile("RTT (\\d+)/(\\d+) ms\\b");
    static final class Reading {
        String match, lastBar, staleBar;
        double own,opponent=Double.NaN;
        double observe(String next,boolean first,double local,String bar){
            if(!next.equals(match)){
                staleBar=lastBar;
                match=next;own=local;opponent=Double.NaN;
            }else if(own!=local){
                own=local;opponent=Double.NaN;
            }
            if(bar!=null && !bar.equals(staleBar)){
                staleBar=null;
                double published=read(bar,first,local);
                if(Double.isFinite(published))opponent=published;
            }
            lastBar=bar;
            return opponent;
        }
    }
    private static final Reading current=new Reading();
    private static String lastMatch, seededMatch;
    private static DuelNetwork seededLeague;
    private static double previous=Double.NaN;
    static double read(String bar,boolean first,double own){
        Matcher m=PAIR.matcher(bar);
        if(!m.find())return Double.NaN;
        int a=Integer.parseInt(m.group(1)),b=Integer.parseInt(m.group(2));
        return (first?a:b)==(int)own?(first?b:a):Double.NaN;
    }
    public static int step(DuelNetwork network,double x,double y,double z,double tx,double ty,double tz,
                           double vx,double vy,double vz,boolean ground,boolean sprint,
                           double ownAge,double targetAge,double own){
        if(network.league()!=null && (network!=seededLeague || !Client.match.equals(seededMatch))){
            String seat=Client.controls.getProperty("archive_side","");
            long seed=(Client.match+"#"+seat).hashCode();
            network.league().seed(seed);
            Client.log(Compat.map("type","sumo_league_seed","match",Client.match,"seed",seed));
            seededLeague=network;
            seededMatch=Client.match;
        }
        double opponent=Double.NaN;
        if(DuelNetwork.study(network.family())||network.league()!=null){
            long now=TrainingClock.nanos();double age=Client.lastClick==0?Double.POSITIVE_INFINITY:(now-Client.lastClick)/1e6;
            network.localTiming(now/1e6,age);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("type","duel_local_timing");row.put("tick",Client.ticks);row.put("match",Client.match);
            row.put("head_ns",now);row.put("last_press_ns",Client.lastClick);Client.log(row);
        }
        if(network.family()==DuelNetwork.Family.PING_MLP||network.league()!=null){
            String bar=String.valueOf(R.get(R.get(Client.mc,"ingameGUI"),"recordPlaying"));
            opponent=current.observe(Client.match,"a".equals(Client.controls.getProperty("archive_side")),own,bar);
            if(!Client.match.equals(lastMatch)||Double.compare(previous,opponent)!=0){
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("type","duel_hud_ping");row.put("match",Client.match);row.put("tick",Client.ticks);
                row.put("time",System.currentTimeMillis());row.put("hud",bar);row.put("own",own);
                row.put("opponent",Double.isFinite(opponent)?opponent:-1);Client.log(row);
                lastMatch=Client.match;previous=opponent;
            }
        }
        return network.step(x,y,z,tx,ty,tz,vx,vy,vz,ground,sprint,ownAge,targetAge,own,opponent);
    }
}
