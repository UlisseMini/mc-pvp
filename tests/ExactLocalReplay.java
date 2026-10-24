import lab.ExactTravel;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Boundary-conditioned local trajectory replay. Initializes position/motion ONCE.
 * Thereafter only measured incoming S12s, contact sprint flags, and travel inputs
 * are supplied. No subsequent measured position or velocity is assimilated.
 * This is deliberately not a counterfactual duel: changing an action requires
 * regenerating contacts, sprint metadata, remote views, and outgoing packets.
 */
public final class ExactLocalReplay {
    private static ExactTravel.State state;
    private static double d(String[] f,int i){return Double.parseDouble(f[i]);}
    private static float p(String[] f,int i){return Float.parseFloat(f[i]);}
    private static boolean b(String[] f,int i){
        if(!f[i].equals("true")&&!f[i].equals("false"))throw new IllegalArgumentException("Bad boolean");
        return Boolean.parseBoolean(f[i]);
    }
    private static ExactTravel.State motion(double x,double y,double z){
        return new ExactTravel.State(state.x(),state.y(),state.z(),x,y,z,state.box(),state.onGround(),
                state.isCollidedHorizontally(),state.isCollidedVertically(),state.isCollided(),
                state.noClip(),state.isInWeb(),state.sneaking());
    }
    private static double cutoff(double v){return Math.abs(v)<.005?0:v;}
    private static String values(){
        var q=state.box();
        return state.x()+"\t"+state.y()+"\t"+state.z()+"\t"+state.vx()+"\t"+state.vy()+"\t"+state.vz()
                +"\t"+q.minX()+"\t"+q.minY()+"\t"+q.minZ()+"\t"+q.maxX()+"\t"+q.maxY()+"\t"+q.maxZ()
                +"\t"+state.onGround()+"\t"+state.isCollidedHorizontally()+"\t"+state.isCollidedVertically()+"\t"+state.isCollided();
    }
    public static void main(String[] args)throws Exception{
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in));String line;
        while((line=in.readLine())!=null){
            String[] f=line.split("\t");
            switch(f[0]){
                case "INIT" -> state=new ExactTravel.State(d(f,1),d(f,2),d(f,3),d(f,4),d(f,5),d(f,6),
                        new ExactTravel.Box(d(f,7),d(f,8),d(f,9),d(f,10),d(f,11),d(f,12)),
                        b(f,13),b(f,14),b(f,15),b(f,16),b(f,17),b(f,18),b(f,19));
                case "S12" -> state=motion(d(f,1),d(f,2),d(f,3));
                case "CONTACT" -> {if(b(f,1))state=motion(state.vx()*.6,state.vy(),state.vz()*.6);}
                case "MOVE" -> {
                    state=motion(cutoff(state.vx()),cutoff(state.vy()),cutoff(state.vz()));
                    state=ExactTravel.predict(state,new ExactTravel.Input(p(f,1),p(f,2),p(f,3),p(f,4),p(f,5)),
                            new ExactTravel.Environment(d(f,6),p(f,7),b(f,8),b(f,9),b(f,10),b(f,11),b(f,12)));
                }
                default -> throw new IllegalArgumentException("Unknown event "+f[0]);
            }
            System.out.println(values());
        }
    }
}
