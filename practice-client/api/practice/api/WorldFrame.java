package practice.api;
import java.util.*;
/** Immutable world-space drawing primitives; never entities or mutable game objects. Colors are ARGB. */
public final class WorldFrame {
    public final List<Line> lines;public final List<Box> boxes;
    public WorldFrame(List<Line> lines,List<Box> boxes){this.lines=Collections.unmodifiableList(new ArrayList<>(lines));this.boxes=Collections.unmodifiableList(new ArrayList<>(boxes));}
    public static final class Line {
        public final double x,y,z,xx,yy,zz;public final int color;
        public Line(double x,double y,double z,double xx,double yy,double zz,int color){this.x=x;this.y=y;this.z=z;this.xx=xx;this.yy=yy;this.zz=zz;this.color=color;}
    }
    public static final class Box {
        public final double x,y,z,width,height,depth,yaw;public final int color;
        public Box(double x,double y,double z,double width,double height,double depth,double yaw,int color){this.x=x;this.y=y;this.z=z;this.width=width;this.height=height;this.depth=depth;this.yaw=yaw;this.color=color;}
    }
}
