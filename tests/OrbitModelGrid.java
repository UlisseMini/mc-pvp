package lab;

/** Small physics-motivated timing/range screen. Results are hypotheses only. */
public final class OrbitModelGrid {
    public static void main(String[] args){
        for(int cycle:new int[]{500,600,650,700,750,800})
            for(double spacing:new double[]{2.7,2.8,2.9,2.98})
                for(int repair:new int[]{0,2})
                    for(boolean ctrl:new boolean[]{false,true}){
                        System.setProperty("orbit.model_cycle_ms",Integer.toString(cycle));
                        for(int[] ping:new int[][]{{20,20},{100,20},{20,100}})
                            for(int[] phase:new int[][]{{25,10},{0,33},{40,17}}){
                                String name="cycle"+cycle+"-range"+spacing+"-repair"+repair+"-ctrl"+ctrl;
                                System.out.println(Json.encode(OrbitModelSearch.trial(name,1,true,false,ctrl,repair,0,spacing,
                                    true,2000,ping[0],ping[1],phase[0],phase[1],20)));
                            }
                    }
    }
}
