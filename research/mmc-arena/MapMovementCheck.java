import java.util.*;
import lab.ExactTravel;
import lab.RecordedSumoMap;

/** Test the map solver against unmodified Entity.moveEntity and World.getCollidingBoundingBoxes. */
final class MapMovementCheck {
    static final class Body extends pk {
        Body(adm world){super(world);a(.6f,1.8f);S=.6f;}
        protected void h(){} protected void a(dn tag){} protected void b(dn tag){}
        protected boolean s_(){return false;} // no footsteps
        public boolean U(){return false;} // no rain in this fixture
    }
    static void compare(String name)throws Exception{
        var map=RecordedSumoMap.load(name);
        var world=new BlockGeometry(map);
        var terrain=new lab.RecordedCollisions(name,4096,11);
        var environment=new ExactTravel.Environment(12,.6f,true,true,false,false,false,new ExactTravel.Disk(4096,13,terrain));
        var rng=new Random(751239L);
        Body entity=new Body(world);int tested=0,horizontal=0,landings=0;
        for(int z=map.z0;z<map.z0+map.depth;z++)for(int x=map.x0;x<map.x0+map.width;x++)for(int k=0;k<12;k++){
            double px=4096+x+rng.nextDouble(),py=11.5+.25*(k%7),pz=z+rng.nextDouble();
            double vx=rng.nextDouble()*2-1,vy=rng.nextDouble()*1.2-.85,vz=rng.nextDouble()*2-1;
            boolean ground=k%2==0;
            entity.b(px,py,pz);entity.v=vx;entity.w=vy;entity.x=vz;entity.C=ground;
            aug a=entity.aR();
            var initial=new ExactTravel.State(px,py,pz,vx,vy,vz,new ExactTravel.Box(a.a,a.b,a.c,a.d,a.e,a.f),ground,false,false,false,false,false,false);
            var predicted=ExactTravel.predict(initial,new ExactTravel.Input(0,0,0,.1f,.02f),environment);
            entity.d(vx,vy,vz);var b=entity.aR();var q=predicted.box();
            if(q.minX()!=b.a||q.minY()!=b.b||q.minZ()!=b.c||q.maxX()!=b.d||q.maxY()!=b.e||q.maxZ()!=b.f||
                predicted.isCollidedHorizontally()!=entity.D||predicted.isCollidedVertically()!=entity.E||predicted.onGround()!=entity.C)
                throw new AssertionError(name+" "+x+","+z+" sample "+k+" predicted "+q+" native "+b+" flags "+predicted.onGround()+"/"+entity.C);
            tested++;if(entity.D)horizontal++;if(entity.C)landings++;
        }
        if(horizontal==0||landings==0)throw new AssertionError("Missing collision coverage");
        System.out.println("GEOMETRY-CHECK "+name+" "+tested+" "+horizontal+" "+landings);
    }
    public static void main(String[] args)throws Exception{kb.c();for(String name:args)compare(name);}
}
