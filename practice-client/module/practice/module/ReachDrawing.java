package practice.module;

import practice.api.WorldFrame;
import java.util.*;

/** Shared live/replay reach presentation. Input poses remain untouched; all metrics use their logical positions elsewhere. */
public final class ReachDrawing {
    private ReachDrawing(){}

    /** Draw a filled reach field aligned with player rendering, without outlines or diagnostic rays. */
    public static void append(List<WorldFrame.Sphere> spheres,Map<String,Object> source,double reach,int color){
        double[] eye=eye(source);
        if(eye==null||!Double.isFinite(reach)||reach<=0)return;
        spheres.add(new WorldFrame.Sphere(eye[0],eye[1],eye[2],reach,color));
    }

    private static double[] eye(Map<String,Object> pose){
        double[] p=vector(pose,"render",3);if(p==null)p=vector(pose,"pos",3);
        if(p!=null)p[1]+=number(pose,"eye_height",1.62);return p;
    }
    private static double number(Map<String,Object> pose,String key,double fallback){
        Object v=pose.get(key);double n=v instanceof Number?((Number)v).doubleValue():fallback;return Double.isFinite(n)?n:fallback;
    }
    private static double[] vector(Map<String,Object> pose,String key,int length){
        Object value=pose.get(key);if(!(value instanceof List))return null;List<?> a=(List<?>)value;if(a.size()!=length)return null;
        double[] out=new double[length];for(int i=0;i<length;i++){if(!(a.get(i) instanceof Number))return null;out[i]=((Number)a.get(i)).doubleValue();if(!Double.isFinite(out[i]))return null;}return out;
    }
}
