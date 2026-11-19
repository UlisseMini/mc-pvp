import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import lab.DuelNetwork;
import lab.Json;

/** Diagnose an already fitted strafe readout on exposed clocks. Both experts observe the applied action. */
final class EdgeFitAblation {
    static final String[] MODES={"parent","fit","fit-in-window","fit-outside-window","center-in-window","wtap"};
    static final class Probe extends DuelEnv.Player {
        final Arena.Neural parent, fit;
        final DuelEnv.Player tap;
        double cx,cz;
        int mode,heads,entry;
        final int[] differences = new int[3];
        int totalWindow, parentCenter, fitCenter, nonzeroWindow;
        List<Object> firstDifference;
        Probe(Arena.Model old,Arena.Model trained,int rtt) {
            super("edge-ablation",0,rtt,0);
            parent=new Arena.Neural(old,rtt);fit=new Arena.Neural(trained,rtt);
            tap=new DuelEnv.Player("wtap",0,rtt,0);
        }
        Probe(Probe p){
            super(p.style,0,p.rtt,0);parent=p.parent.copy();fit=p.fit.copy();tap=p.tap.copy();
            cx=p.cx;cz=p.cz;mode=p.mode;heads=p.heads;entry=p.entry;firstClick=p.firstClick;
            System.arraycopy(p.differences,0,differences,0,3);totalWindow=p.totalWindow;
            parentCenter=p.parentCenter;fitCenter=p.fitCenter;nonzeroWindow=p.nonzeroWindow;firstDifference=p.firstDifference;
        }
        @Override Probe copy(){return new Probe(this);}
        @Override public PeriodicComboClock.Control input(PeriodicComboClock.Observation o) {
            var q=o.own();var a=parent.input(o);var b=fit.input(o);var w=tap.input(o);
            if(a.key()!=b.key()||a.click()!=b.click()||a.jump()!=b.jump())throw new AssertionError("Changed another channel");
            if(++heads==1){cx=(q.x()+o.targetX())/2;cz=(q.z()+o.targetZ())/2;}
            double x=q.x()-cx,z=q.z()-cz,r=Math.hypot(x,z);double outward=r>0?(x*q.vx()+z*q.vz())/r:0;
            boolean hurt=Double.isFinite(o.lastOwnHurtTime())||Double.isFinite(o.lastTargetHurtTime());
            if(entry==0 && hurt && r>=3.5 && outward>=.02)entry=heads;
            int region=entry==0?0:heads-entry<6?1:2;
            int toward=(int)Math.signum(-x*(o.targetZ()-q.z())+z*(o.targetX()-q.x()));
            if(region==1){totalWindow++;if(toward!=0){
                nonzeroWindow++;if(Math.signum(a.strafe())==toward)parentCenter++;
                if(Math.signum(b.strafe())==toward)fitCenter++;
            }}
            if(a.strafe()!=b.strafe()){
                differences[region]++;
                if(firstDifference==null)firstDifference=List.of(o.time(),heads,region,r,outward,hurt,
                        (int)Math.signum(a.strafe()),(int)Math.signum(b.strafe()),toward);
            }
            var applied=a;
            if(mode==1 || mode==2&&region==1 || mode==3&&region!=1)applied=b;
            if(mode==4&&region==1)applied=new PeriodicComboClock.Control(a.key(),.98f*toward,a.click(),a.jump());
            if(mode==5)applied=w;
            int keys=DuelNetwork.pack(applied.key(),(int)Math.signum(applied.strafe()),applied.click(),applied.jump());
            parent.net.applied(keys);fit.net.applied(keys);
            return applied;
        }
    }

    static void replayNative(String[] args)throws Exception{
        var parent=DuelNetwork.decode(java.nio.file.Files.readString(Path.of(args[1])).trim());
        var fit=DuelNetwork.decode(java.nio.file.Files.readString(Path.of(args[2])).trim());
        boolean loggedFit=args[3].equals("fit");
        var in=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        for(String line;(line=in.readLine())!=null;){
            String[] cols=line.split(" ");double[] v=new double[14];
            for(int i=0;i<v.length;i++)v[i]=Double.parseDouble(cols[i]);
            int applied=Integer.parseInt(cols[14]);int[] actions=new int[2];
            for(int i=0;i<2;i++){
                DuelNetwork n=i==0?parent:fit;
                actions[i]=n.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9]!=0,v[10]!=0,
                        v[11]==-1?Double.POSITIVE_INFINITY:v[11],v[12]==-1?Double.POSITIVE_INFINITY:v[12],v[13]);
                n.applied(applied);
            }
            if(actions[loggedFit?1:0]!=applied)throw new AssertionError("Network did not reproduce its own logged inputs");
            if(DuelNetwork.key(actions[0])!=DuelNetwork.key(actions[1])||DuelNetwork.jump(actions[0])!=DuelNetwork.jump(actions[1])
                    ||DuelNetwork.click(actions[0])!=DuelNetwork.click(actions[1]))throw new AssertionError("Changed another input channel");
            System.out.println(DuelNetwork.side(actions[0])+" "+DuelNetwork.side(actions[1]));
        }
    }

    public static void main(String[] argv)throws Exception{
        if(argv[0].equals("--native")){replayNative(argv);return;}
        var pair=Matches.roster(Path.of(argv[0]));var foes=Matches.roster(Path.of(argv[1]));
        for(var env:Arena.envs(Arrays.stream(argv,2,argv.length).map(Path::of).toList(),"mmc",true)){
            env.initial.timing.mouseInput=true;
            for(var foe:foes)for(boolean seat:new boolean[]{true,false}){
                var start=env.new Game(seat,new Probe(pair.get(0).model(),pair.get(1).model(),env.rtt),foe.create(env.rtt,0));
                String hash=TradeWords.stateHash(start);
                var row=new LinkedHashMap<String,Object>();row.put("clock",env.clockId);row.put("rtt",env.rtt);
                row.put("opponent",foe.name());row.put("side",seat?"a":"b");row.put("spawn_fork_sha256",hash);
                var branches=new LinkedHashMap<String,Object>();
                for(int mode=0;mode<MODES.length;mode++){
                    var g=env.new Game(start,start.a.copy(),start.b.copy());
                    if(!TradeWords.stateHash(g).equals(hash))throw new AssertionError("Different full start");
                    var p=(Probe)g.our();p.mode=mode;g.finish();
                    var m=new LinkedHashMap<String,Object>();m.put("score",g.reward());m.put("end",g.clock.time());
                    m.put("fresh",seat?List.of(g.clock.state.hits,g.clock.state.incomingHits):List.of(g.clock.state.incomingHits,g.clock.state.hits));
                    m.put("first_difference",p.firstDifference);m.put("different_strafe",p.differences);
                    m.put("window",List.of(p.totalWindow,p.nonzeroWindow,p.parentCenter,p.fitCenter));branches.put(MODES[mode],m);
                }
                if(!TradeWords.stateHash(start).equals(hash))throw new AssertionError("Changed shared start");
                row.put("branches",branches);System.out.println(Json.encode(row));
            }
        }
    }
}
