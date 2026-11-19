import java.nio.file.*;
import java.util.*;
import lab.Json;

/** A bounded clairvoyant diagnosis of established combos. Never exported as a playing controller. */
final class BoxingOracle {
    static final int HEADS=2, WIDTH=8, LAYERS=32;
    static final String[] NAMES={"wtap","pressure","walk","coast","back","quiet-forward","jump","strafe"};
    interface Applied {
        void accept(PeriodicComboClock.Observation view,PeriodicComboClock.Control action,int word);
    }
    static final class Driver extends DuelEnv.Player{
        final DuelEnv.Player wt;
        int word,heads;Applied trace;
        Driver(DuelEnv.Player wt){super("oracle-driver",0,wt.rtt,0);this.wt=wt;firstClick=wt.firstClick;}
        public void received(PeriodicComboClock.PacketObservation packet){wt.received(packet);}
        public PeriodicComboClock.Control input(PeriodicComboClock.Observation obs){
            heads++;var baseline=wt.input(obs);
            int key=word==2?1:word==3?0:word==4?3:2;
            var result=word==0?baseline:new PeriodicComboClock.Control(key,word==7?.98f:0,word!=5,word==6&&obs.own().onGround());
            if(trace!=null)trace.accept(obs,result,word);
            return result;
        }
        Driver copy(){Driver d=new Driver(wt.copy());d.word=word;d.heads=heads;d.trace=trace;return d;}
    }
    static final class Node{
        final BoxingDrill.Checkpoint start;
        final DuelEnv.Game g;
        final List<Integer> words;final boolean active;
        int cycles,seen;double last;String terminal;double end;
        Node(BoxingDrill.Checkpoint s,boolean active){
            this.active=active;
            start=s;var old=s.state;var a=old.a.copy();var b=old.b.copy();
            if(old.first)a=new Driver(a);else b=new Driver(b);
            g=s.env.new Game(old,a,b);g.trace();g.hits.list.addAll(s.committed);words=List.of();last=s.landmark;consume();
        }
        Node(Node parent,List<Integer> w){
            start=parent.start;active=parent.active;var old=parent.g;g=start.env.new Game(old,old.a.copy(),old.b.copy());g.trace();
            words=w;cycles=parent.cycles;last=parent.last;terminal=parent.terminal;end=parent.end;
        }
        void consume(){
            if(terminal!=null)return;
            while(seen<g.hits.list.size()){
                var h=g.hits.list.get(seen++);
                if(active && h.at()-last>1000){terminal="inactive";end=last+1000;return;}
                if(h.sideA()!=g.first){terminal="return";end=h.at();return;}
                if(!h.upgrade()){cycles++;last=h.at();}
            }
            if(active && g.clock.time()-last>1000){terminal="inactive";end=last+1000;}
            else if(g.clock.time()>=start.env.limit){terminal="censored";end=start.env.limit;}
        }
        Node play(int word){
            var words=new ArrayList<Integer>(this.words);words.add(word);
            Node next=new Node(this,List.copyOf(words));((Driver)next.g.our()).word=word;
            var driver=(Driver)next.g.our();int limit=driver.heads+HEADS;
            while(next.terminal==null && driver.heads<limit){
                if(next.g.step>=start.env.serverEnds.length){next.g.clock.runUntil(start.env.limit);next.consume();break;}
                if(!next.g.clock.runHeadInterval(next.g.first,start.env.serverEnds[next.g.step]))next.g.step++;
                next.consume();
            }
            if(driver.heads>limit || (next.terminal==null && driver.heads!=limit))throw new AssertionError("Wrong number of applied heads");
            return next;
        }
        BoxingDrill.Result tail(){
            var n=new Node(this,words);((Driver)n.g.our()).word=0;
            while(n.terminal==null && n.g.advance())n.consume();
            if(n.terminal==null)throw new IllegalStateException("Unresolved boxing suffix");
            return new BoxingDrill.Result(n.cycles,n.end-start.landmark,n.terminal);
        }
    }
    record Entry(Node node,BoxingDrill.Result result){}
    static int quality(BoxingDrill.Result a,BoxingDrill.Result b){
        int c=Integer.compare(a.cycles(),b.cycles());return c!=0?c:Double.compare(a.ms(),b.ms());
    }
    static Map<String,Object> search(BoxingDrill.Checkpoint start){
        var root=new Node(start,true);var base=root.tail();
        if(!base.equals(start.baseline))throw new AssertionError("No-op oracle prefix changed W-tap");
        var best=new Entry(root,base);var live=List.of(best);int examined=0;
        for(int depth=0;depth<LAYERS&&!live.isEmpty();depth++){
            var next=new ArrayList<Entry>();
            for(var e:live)for(int i=0;i<NAMES.length;i++){
                var n=e.node.play(i);var result=n.tail();examined++;var item=new Entry(n,result);
                if(quality(result,best.result)>0)best=item;
                if(n.terminal==null)next.add(item);
            }
            next.sort((a,b)->-quality(a.result,b.result));
            // A diversity heuristic only. Equal visible positions can conceal different pending queues.
            var unique=new HashSet<String>();var beam=new ArrayList<Entry>();
            for(var e:next){
                var plant=e.node.g.clock.state;var own=e.node.g.first?plant.a:plant.b;var other=e.node.g.first?plant.b:plant.a;
                String digest=own.q.x()+"/"+own.q.z()+"/"+own.q.vx()+"/"+own.sprint+"/"+other.q.x()+"/"+other.q.vx()+"/"+e.result;
                if(unique.add(digest)){beam.add(e);if(beam.size()==WIDTH)break;}
            }
            live=beam;
        }
        var replay=new Node(start,true);for(int word:best.node.words)replay=replay.play(word);
        if(!replay.tail().equals(best.result))throw new AssertionError("Selected action sequence failed replay");
        Map<String,Object> row=new LinkedHashMap<>();row.put("clock",start.env.clockId);row.put("rtt",start.env.rtt);
        row.put("side",start.state.first?"a":"b");row.put("k",start.k);row.put("landmark_ms",start.landmark);row.put("examined",examined);
        row.put("wtap",List.of(base.cycles(),base.ms(),base.terminal()));row.put("hindsight",List.of(best.result.cycles(),best.result.ms(),best.result.terminal()));
        var returned=new Node(start,false);var wtReturn=returned.tail();
        for(int w:best.node.words)returned=returned.play(w);
        var other=returned.tail();row.put("return_only_wtap",List.of(wtReturn.cycles(),wtReturn.ms(),wtReturn.terminal()));
        row.put("return_only_hindsight",List.of(other.cycles(),other.ms(),other.terminal()));
        row.put("words",best.node.words.stream().map(i->NAMES[i]).toList());return row;
    }
    public static void main(String[] args)throws Exception{
        for(Path p:Arrays.stream(args).map(Path::of).toList()){
            var tape=DuelEnv.Tape.read(p,20000);
            for(int ping:new int[]{0,80})for(boolean a:new boolean[]{true,false}){
                var e=new DuelEnv(tape,"mmc",ping,false);var starts=BoxingDrill.acquire(e,a,true);
                for(var s:starts)System.out.println(Json.encode(search(s)));
            }
        }
    }
}
