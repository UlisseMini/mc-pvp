import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import lab.DuelNetwork;
import lab.PairedExperts;

/** Same-state labels for choosing between already primed controllers. All training views are local. */
final class ContinuationTeacher {
    static final int[] SIGNALS={0,2,5,6,10,11,14};
    static final class Student extends AdaptationBranches.Pilot {
        final float[] gate;
        final double[] fast,slow;
        int remaining,used;
        double[] example;
        boolean record;
        Student(String encoded,int rtt){
            super(encoded,rtt);gate=brain.weights();fast=new double[SIGNALS.length];slow=fast.clone();
        }
        Student(Student p){
            super(p);gate=p.gate;fast=p.fast.clone();slow=p.slow.clone();remaining=p.remaining;used=p.used;
            example=p.example==null?null:p.example.clone();record=p.record;
        }
        @Override Student copy(){return new Student(this);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o){
            var action=super.input(o);
            double[] view=brain.features();
            for(int i=0;i<SIGNALS.length;i++){
                double v=view[SIGNALS[i]];
                if(totalHeads==1){fast[i]=v;slow[i]=v;}
                else {fast[i]+=.32*(v-fast[i]);slow[i]+=.065*(v-slow[i]);}
            }
            if(record && example==null){
                example=new double[PairedExperts.GATE];example[0]=1;
                System.arraycopy(view,0,example,1,view.length);int k=1+view.length;
                for(int i=0;i<SIGNALS.length;i++){example[k++]=slow[i];example[k++]=fast[i]-slow[i];}
                double score=0;for(int i=0;i<k;i++)score+=example[i]*gate[PairedExperts.HEADER+i];
                if(Math.abs(score-brain.pair().score())>1e-8)throw new AssertionError("Teaching view differs from deployed selector");
            }
            if(remaining>0){used++;if(--remaining==0)forced=-1;}
            return action;
        }
    }
    static DuelEnv.Game copy(DuelEnv e,DuelEnv.Game g){return e.new Game(g,g.a.copy(),g.b.copy());}
    static List<String> one(DuelEnv e,Arena.Fighter opp,boolean seat,String brain,int seed,int index)throws Exception{
        var rand=new Random(1729L*seed+293L*index);int[] marks={150+rand.nextInt(301),600+rand.nextInt(401),
            1300+rand.nextInt(901),2500+rand.nextInt(1751),5000+rand.nextInt(4001)};
        var game=e.new Game(seat,new Student(brain,e.rtt),opp.create(e.rtt,0,true,e.initial.config.sumoDiameter));
        var forks=new ArrayList<DuelEnv.Game>();
        while(forks.size()<marks.length && game.advance()){
            if(game.clock.time()>=marks[forks.size()] && game.winner()<0)forks.add(copy(e,game));
        }
        game.finish();
        var lines=new ArrayList<String>();
        for(int i=0;i<marks.length;i++){
            String meta=e.clockId+" "+opp.name()+" "+(seat?"a":"b")+" "+e.rtt+" "+i+" "+marks[i];
            if(i>=forks.size()){lines.add(meta+" absent");continue;}
            var start=forks.get(i);int length=new int[]{3,8,20}[(Math.floorMod(index,3)+i)%3];
            double[] labels=new double[4];double[] x=null;
            String audited="-";boolean noHead=false;
            for(int branch=0;branch<4;branch++){
                var b=copy(e,start);var us=(Student)b.our();us.record=true;
                us.forced=branch==3?-1:branch;us.remaining=branch<2?length:0;
                b.finish();labels[branch]=b.reward();
                if(us.example==null){noHead=true;if(us.used!=0)throw new AssertionError();continue;}
                if(noHead || (x!=null && !Arrays.equals(x,us.example)))throw new AssertionError("Different decision observation");
                x=us.example;
                if(branch==3 && (b.reward()!=game.reward() || b.clock.time()!=game.clock.time()))throw new AssertionError("Unchanged branch diverged");
                if(branch==3 && index%113==0){
                    synchronized(FullClockHash.class){
                        audited=FullClockHash.digest(b.clock);
                        if(!audited.equals(FullClockHash.digest(game.clock)))throw new AssertionError("Unchanged state diverged");
                    }
                }
            }
            if(noHead){if(x!=null)throw new AssertionError();lines.add(meta+" nohead");continue;}
            var row=new StringBuilder(meta).append(" ok ").append(start.clock.time()).append(' ').append(length).append(' ').append(audited);
            for(double l:labels)row.append(' ').append(l);
            for(double v:x)row.append(' ').append(v);
            lines.add(row.toString());
        }
        return lines;
    }
    public static void main(String[] a)throws Exception{
        String arms=Files.readString(Path.of(a[0])).trim();var opps=Matches.roster(Path.of(a[1]));
        int seed=Integer.parseInt(a[2]);var tapes=Arrays.stream(a,3,a.length).map(Path::of).toList();
        var pool=Executors.newFixedThreadPool(3);int index=0;
        try{
            for(var e:Arena.envs(tapes,"mmc",true)){
                e.initial.timing.mouseInput=true;var jobs=new ArrayList<Callable<List<String>>>();
                for(var opp:opps)for(boolean seat:new boolean[]{true,false}){
                    int id=index++;jobs.add(()->one(e,opp,seat,arms,seed,id));
                }
                for(var batch:pool.invokeAll(jobs))for(String row:batch.get())System.out.println(row);
            }
        }finally{pool.shutdown();}
    }
}
