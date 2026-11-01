package practice.math;

/** Aim-independent eye-to-expanded-hitbox geometry. Flat arena, not a hit verdict. */
public final class ReachMath {
    private ReachMath(){}
    public static double distance(double[] eye,double[] box){
        double sum=0;
        for(int i=0;i<3;i++){double d=Math.max(box[i]-eye[i],Math.max(0,eye[i]-box[i+3]));sum+=d*d;}
        return Math.sqrt(sum);
    }
    public static double[] expand(double[] box,double border){
        return new double[]{box[0]-border,box[1]-border,box[2]-border,box[3]+border,box[4]+border,box[5]+border};
    }
}
