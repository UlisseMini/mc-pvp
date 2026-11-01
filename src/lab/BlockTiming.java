package lab;

import java.util.Map;

/** Describes observed server-side guard edges; never invents an optimal block time. */
public final class BlockTiming {
    static final long NEAR_MS=250,DISPLAY_MS=1000;
    long started,released,missed,missDistance=Long.MAX_VALUE,shownAt,delta;
    String kind="";
    boolean active;
    void show(String next,long difference,long now){kind=next;delta=difference;shownAt=now;}
    public boolean transition(boolean blocking,long now){
        if(blocking==active)return false;
        active=blocking;
        if(blocking){
            started=now;
            long late=now-missed;
            if(missed>0&&late>=0&&late<=NEAR_MS&&late<missDistance){
                missDistance=late;show("late",late,now);return true;
            }
        }else released=now;
        return false;
    }
    public void hit(boolean blocked,long now){
        if(blocked){
            missed=0;missDistance=Long.MAX_VALUE;
            show("blocked",Math.max(0,now-started),now);
        }else{
            missed=now;missDistance=Long.MAX_VALUE;
            if(released>0&&now-released<=NEAR_MS){
                missDistance=now-released;show("early",missDistance,now);
            }else show("unblocked",0,now);
        }
    }
    public String text(long now){
        if(kind.isEmpty()||now-shownAt>DISPLAY_MS)return "";
        long approximate=Math.round(delta/10.0)*10;
        return switch(kind){
            case "blocked" -> "§a§lBLOCKED §r§a· held ~"+approximate+" ms";
            case "early" -> "§6§lEARLY §r§6· released ~"+approximate+" ms before hit";
            case "late" -> "§c§lLATE §r§c· blocked ~"+approximate+" ms after hit";
            default -> "§cHIT §7· no nearby block";
        };
    }
    public Map<String,Object> state(){return Map.of("kind",kind,"delta_ms",delta,"time",shownAt,"basis","server_observed_guard_edges");}
}
