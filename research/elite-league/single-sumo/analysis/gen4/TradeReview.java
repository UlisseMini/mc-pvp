import java.nio.file.*;
import java.util.*;
import lab.Json;

/** Full, instrumented duels: distinguish spending a press, local sprint drag and fresh server damage. */
final class TradeReview {
    static Map<String,Object> game(String[] r)throws Exception {
        boolean seat=r[3].equals("a"),detail=r[9].equals("detail");
        int a=Integer.parseInt(r[4]),b=Integer.parseInt(r[5]);
        var e=new DuelEnv(LeagueWorker.clock(r[2]),seat?a:b,seat?b:a);
        var own=new Gen4Review.Watched(LeagueWorker.player(Gen4Review.fighter(r[0]),a,b,e,Long.parseLong(r[6])),r[8],false);
        var foe=new Gen4Review.Watched(LeagueWorker.player(Gen4Review.fighter(r[1]),b,a,e,Long.parseLong(r[7])),"intact",false);
        var g=e.new Game(seat,own,foe);
        // In this pinned explicit calendar the cached body immediately preceding CONSUME is its input state.
        boolean[] sprint=new boolean[2];double[] vx=new double[2],vz=new double[2];
        var presses=new ArrayList<>();var hits=new ArrayList<>();
        int[][] tally=new int[2][7];double[][] push=new double[2][2];
        g.trace();g.clock.setAudit((t,event,st)->{
            int old=g.hits.list.size();g.hits.event(t,event,st);
            for(int j=old;j<g.hits.list.size();j++){
                var h=g.hits.list.get(j);if(h.upgrade())continue;
                int i=h.sideA()==seat?0:1;var impulse=h.sideA()?st.lastFreshB:st.lastFreshA;
                tally[i][4]++;if(impulse.attackerSprint())tally[i][5]++;
                push[i][0]+=Math.hypot(impulse.x(),impulse.z());push[i][1]+=impulse.serverSeparation();
                if(detail)hits.add(List.of(t,i,impulse.attackerSprint(),impulse.serverSeparation(),Math.hypot(impulse.x(),impulse.z()),
                    impulse.y(),st.serverAx,st.serverAz,st.serverBx,st.serverBz,st.serverAy,st.serverBy));
            }
            sprint[0]=st.a.sprint;sprint[1]=st.b.sprint;vx[0]=st.a.q.vx();vz[0]=st.a.q.vz();vx[1]=st.b.q.vx();vz[1]=st.b.q.vz();
        });
        g.clock.setClickAudit(ev->{
            int i=ev.fromA()==seat?0:1,physical=ev.fromA()?0:1;
            if(ev.action().equals("ADMIT")){tally[i][0]++;return;}
            if(!ev.action().equals("CONSUME"))return;
            var obs=g.clock.observation(ev.fromA());var q=obs.own();
            boolean brake=ev.contact()&&sprint[physical];
            double scale=brake?.6:1.;
            if(Math.abs(q.vx()-vx[physical]*scale)>1e-7||Math.abs(q.vz()-vz[physical]*scale)>1e-7)
                throw new IllegalStateException("Unattributed motion at click consumption");
            if(ev.contact())tally[i][1]++;
            if(brake)tally[i][2]++;
            if(brake&&(vx[physical]*(obs.targetX()-q.x())+vz[physical]*(obs.targetZ()-q.z())<0))tally[i][3]++;
            if(!ev.contact()&&ev.eligible())tally[i][6]++;
            if(detail)presses.add(List.of(ev.time(),i,ev.eligible(),ev.contact(),brake,obs.nearest(),q.x(),q.y(),q.z()));
            sprint[physical]=obs.sprint();vx[physical]=q.vx();vz[physical]=q.vz();
        });
        g.finish();
        return Map.of("outcome",g.reward(),"end",(double)(float)g.clock.time(),"counts",tally,"impulses",push,"presses",presses,"fresh",hits);
    }
    public static void main(String[] args)throws Exception{
        LeagueWorker.load();
        try(var in=Files.newBufferedReader(Path.of(args[0]));var out=Files.newBufferedWriter(Path.of(args[1]))){
            String s;while((s=in.readLine())!=null){out.write(Json.encode(game(s.split("\t"))));out.newLine();}
        }
    }
}
