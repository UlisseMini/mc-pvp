package lab;

/**
 * Independent, float-faithful conditional predictor for official 1.8.9
 * EntityLivingBase.moveEntityWithHeading (pr.g(FF)), ordinary loaded flat floor.
 *
 * Input is state AT g ENTRY, after the living update's .005 motion cutoff and
 * input damping. Do not apply that cutoff or another .98 input multiplier here.
 * This predicts the six motion/position doubles, AABB and collision flags at
 * g RETURN; it does not predict preceding keys/attacks/packets or later pushing.
 * All positions and floorTopY use absolute native world coordinates.
 *
 * Scope: a stationary horizontal plane or single-layer solid sumo disk, uniform
 * slipperiness, open space above it and ordinary block landing (motionY=0).
 * Mounted travel, flight, liquids, webs, ladders and sneak edge-clamping are outside this scope. Callers
 * must establish that world/ability scope independently. Unsupported recorded
 * flags are rejected rather than silently approximated. This is not a general
 * collision engine and is deliberately separate from ComboModel.
 */
public final class ExactTravel {
    private ExactTravel() {}

    private static final float[] SIN_TABLE = new float[65536];
    static {
        // Exact expression order of MathHelper's pinned native initializer.
        for (int i = 0; i < SIN_TABLE.length; i++)
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
    }

    public record Box(double minX, double minY, double minZ,
                      double maxX, double maxY, double maxZ) {
        public Box {
            finite(minX, minY, minZ, maxX, maxY, maxZ);
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Unordered native box");
        }
        private Box offset(double x, double y, double z) {
            return new Box(minX + x, minY + y, minZ + z,
                    maxX + x, maxY + y, maxZ + z);
        }
    }

    /** Immutable snapshot; retained AABB endpoints cannot be recovered from x/z exactly. */
    public record State(double x, double y, double z, double vx, double vy, double vz,
                        Box box, boolean onGround,
                        boolean isCollidedHorizontally, boolean isCollidedVertically,
                        boolean isCollided, boolean noClip, boolean isInWeb, boolean sneaking) {
        public State {
            finite(x, y, z, vx, vy, vz);
            if (box == null) throw new IllegalArgumentException("Native box is required");
        }
    }

    /** These are the raw float g arguments and getters, not ideal key magnitudes. */
    public record Input(float strafe, float forward, float yawDegrees,
                        float groundAcceleration, float airAcceleration) {
        public Input {
            finite(strafe, forward, yawDegrees, groundAcceleration, airAcceleration);
            if (groundAcceleration < 0 || airAcceleration < 0)
                throw new IllegalArgumentException("Negative acceleration");
        }
    }

    /** floorTopY=4 in the lab; the ordinary supporting block's Y is 3. */
    public record Environment(double floorTopY, float floorSlipperiness,
                              boolean loadedColumnBefore, boolean loadedColumnAfter,
                              boolean water, boolean lava, boolean ladder, Disk disk) {
        public Environment(double top,float friction,boolean before,boolean after,boolean water,boolean lava,boolean ladder) {
            this(top,friction,before,after,water,lava,ladder,null);
        }
        public Environment {
            finite(floorTopY, floorSlipperiness);
            if (!(floorSlipperiness > 0))
                throw new IllegalArgumentException("Nonpositive slipperiness");
        }
    }

    /** Solid, single-layer platform in open air. Player step height is the native 0.6f. */
    public interface Terrain {
        java.util.List<Box> boxes(Box sweep);
        float slipperiness(State state);
        boolean supports(Box footprint);
    }
    public record Disk(double centerX,int diameter,Terrain terrain) {
        public Disk(double centerX,int diameter){this(centerX,diameter,null);}
        public Disk { if(diameter<=0)throw new IllegalArgumentException("Empty platform"); }
        boolean cell(int x,int z) {
            // An odd-width ring is centered on a block; an even one lies between four blocks.
            double offset=(diameter%2)*.5;
            double dx=x+.5-(centerX+offset),dz=z+.5-offset,r=diameter*.5;
            return dx*dx+dz*dz<=r*r;
        }
        public boolean supports(Box box) {
            if(terrain!=null)return terrain.supports(box);
            for(int x=(int)Math.floor(box.minX);x<box.maxX;x++)
                for(int z=(int)Math.floor(box.minZ);z<box.maxZ;z++)if(cell(x,z))return true;
            return false;
        }
        // AABB.calculateXOffset/ZOffset, selecting candidate block faces on the swept axis.
        double side(Box box,double delta,double top,boolean xAxis) {
            if(delta==0 || box.minY>=top || box.maxY<=top-1)return delta;
            double lo=xAxis?box.minX:box.minZ,hi=xAxis?box.maxX:box.maxZ;
            int first=(int)Math.floor(lo+Math.min(0,delta));
            double end=hi+Math.max(0,delta);
            int crossMin=(int)Math.floor(xAxis?box.minZ:box.minX);
            double crossMax=xAxis?box.maxZ:box.maxX;
            for(int i=first;i<end;i++)for(int j=crossMin;j<crossMax;j++){
                if(!(xAxis?cell(i,j):cell(j,i)))continue;
                if(delta>0 && hi<=i)delta=Math.min(delta,i-hi);
                else if(delta<0 && lo>=i+1)delta=Math.max(delta,i+1-lo);
            }
            return delta;
        }
        private Clip move(Box b,double dx,double dy,double dz,double top) {
            double y=dy;
            if(dy<0 && b.minY>=top && b.minY+dy<=top && supports(b))y=Math.max(dy,top-b.minY);
            Box out=b.offset(0,y,0);
            double x=side(out,dx,top,true);out=out.offset(x,0,0);
            double z=side(out,dz,top,false);out=out.offset(0,0,z);
            return new Clip(out,x,y,z);
        }
        Clip collide(State s,double dx,double dy,double dz,double top) {
            if(terrain!=null)return collideTerrain(terrain,s,dx,dy,dz);
            Clip normal=move(s.box,dx,dy,dz,top);
            if((dx==normal.x && dz==normal.z) || !(s.onGround || dy<0 && normal.y!=dy))return normal;
            // Both vanilla step candidates have the same overhead clearance on this disk.
            double up=(double).6f;
            Box raised=s.box.offset(0,up,0);
            double x=side(raised,dx,top,true);raised=raised.offset(x,0,0);
            double z=side(raised,dz,top,false);raised=raised.offset(0,0,z);
            if(normal.x*normal.x+normal.z*normal.z>=x*x+z*z)return normal;
            double down=-up;
            if(raised.minY>=top && supports(raised))down=Math.max(down,top-raised.minY);
            return new Clip(raised.offset(0,down,0),x,down,z);
        }
    }
    private record Clip(Box box,double x,double y,double z) {}
    private static Box sweep(Box b,double x,double y,double z){
        return new Box(b.minX+Math.min(0,x),b.minY+Math.min(0,y),b.minZ+Math.min(0,z),
                       b.maxX+Math.max(0,x),b.maxY+Math.max(0,y),b.maxZ+Math.max(0,z));
    }
    private static double clipAxis(java.util.List<Box> blocks,Box b,double d,int axis){
        for(Box c:blocks){
            if(axis!=0&&(b.maxX<=c.minX||b.minX>=c.maxX) ||
               axis!=1&&(b.maxY<=c.minY||b.minY>=c.maxY) ||
               axis!=2&&(b.maxZ<=c.minZ||b.minZ>=c.maxZ))continue;
            double low=axis==0?b.minX:axis==1?b.minY:b.minZ;
            double high=axis==0?b.maxX:axis==1?b.maxY:b.maxZ;
            double front=axis==0?c.minX:axis==1?c.minY:c.minZ;
            double back=axis==0?c.maxX:axis==1?c.maxY:c.maxZ;
            if(d>0 && high<=front)d=Math.min(d,front-high);
            if(d<0 && low>=back)d=Math.max(d,back-low);
        }
        return d;
    }
    private static Clip moveAcross(java.util.List<Box> blocks,Box box,double dx,double y,double dz){
        Box raised=box.offset(0,y,0);
        double x=clipAxis(blocks,raised,dx,0);raised=raised.offset(x,0,0);
        double z=clipAxis(blocks,raised,dz,2);return new Clip(raised.offset(0,0,z),x,y,z);
    }
    private static Clip collideTerrain(Terrain terrain,State s,double dx,double dy,double dz){
        Box b=s.box;var blocks=terrain.boxes(sweep(b,dx,dy,dz));
        Clip flat=moveAcross(blocks,b,dx,clipAxis(blocks,b,dy,1),dz);
        if((flat.x==dx&&flat.z==dz)||!(s.onGround||dy<0&&flat.y!=dy))return flat;
        double step=(double).6f;
        blocks=terrain.boxes(sweep(b,dx,step,dz));
        Clip a=moveAcross(blocks,b,dx,clipAxis(blocks,sweep(b,dx,0,dz),step,1),dz);
        Clip c=moveAcross(blocks,b,dx,clipAxis(blocks,b,step,1),dz);
        Clip best=a.x*a.x+a.z*a.z>c.x*c.x+c.z*c.z?a:c;
        if(flat.x*flat.x+flat.z*flat.z>=best.x*best.x+best.z*best.z)return flat;
        double down=clipAxis(blocks,best.box,-best.y,1);
        return new Clip(best.box.offset(0,down,0),best.x,down,best.z);
    }

    /**
     * Reproduce setPosition's NEW-box construction. Use for an actual native
     * setPosition or synthetic fixture only, never to replace a measured box
     * before each step: offsetting its two endpoints retains rounding history.
     */
    public static Box boxAt(double x, double y, double z, float width, float height) {
        finite(x, y, z, width, height);
        if (!(width > 0) || !(height > 0))
            throw new IllegalArgumentException("Nonpositive dimensions");
        float halfWidth = width / 2.0f;
        return new Box(x - (double) halfWidth, y, z - (double) halfWidth,
                x + (double) halfWidth, y + (double) height, z + (double) halfWidth);
    }

    public static State predict(State before, Input input, Environment environment) {
        if (before == null || input == null || environment == null)
            throw new IllegalArgumentException("Missing conditional state");
        requireSupported(before, environment);

        // pr.g: all products/divisions below are floats in this exact order.
        float surface=before.onGround && environment.disk!=null && environment.disk.terrain!=null?
                environment.disk.terrain.slipperiness(before):environment.floorSlipperiness;
        float friction = .91f;
        if (before.onGround) friction = surface * .91f;
        float normalization = .16277136f / (friction * friction * friction);
        float acceleration = before.onGround
                ? input.groundAcceleration * normalization : input.airAcceleration;
        float strafe = input.strafe, forward = input.forward;
        double vx = before.vx, vy = before.vy, vz = before.vz;

        // pk.a(FFF), moveFlying. It rounds the rotated increment to float
        // BEFORE widening and adding it to the double motion component.
        float length = strafe * strafe + forward * forward;
        if (length >= 1.0E-4f) {
            length = (float) Math.sqrt((double) length);
            if (length < 1.0f) length = 1.0f;
            length = acceleration / length;
            strafe *= length;
            forward *= length;
            float yawRadians = input.yawDegrees * 3.1415927f / 180.0f;
            float sin = SIN_TABLE[(int) (yawRadians * 10430.378f) & 65535];
            float cos = SIN_TABLE[(int) (yawRadians * 10430.378f + 16384.0f) & 65535];
            vx += (double) (strafe * cos - forward * sin);
            vz += (double) (forward * cos + strafe * sin);
        }

        // Native recomputes this BEFORE movement, so takeoff uses ground
        // friction and landing uses air friction for this complete travel call.
        friction = .91f;
        if (before.onGround) friction = surface * .91f;

        // pk.d(DDD): Y, X, Z clipping and separate AABB offsets. A full plane
        // clips only downward Y; no other box intersects in this scoped world.
        double requestedY = vy, clippedY = requestedY;
        Box box;
        boolean horizontalCollision=false;
        if(environment.disk!=null){
            Clip clipped=environment.disk.collide(before,vx,vy,vz,environment.floorTopY);
            box=clipped.box;clippedY=clipped.y;
            horizontalCollision=vx!=clipped.x || vz!=clipped.z;
            if(vx!=clipped.x)vx=0;
            if(vz!=clipped.z)vz=0;
        } else {
            if (requestedY < 0.0) {
                double gap = environment.floorTopY - before.box.minY;
                if (gap > clippedY) clippedY = gap;
            }
            box = before.box.offset(0.0, clippedY, 0.0);
            box = box.offset(vx, 0.0, 0.0);
            box = box.offset(0.0, 0.0, vz);
        }

        // resetPositionToBB: do not substitute before.x + vx.
        double x = (box.minX + box.maxX) / 2.0;
        double y = box.minY;
        double z = (box.minZ + box.maxZ) / 2.0;
        boolean verticalCollision = requestedY != clippedY;
        boolean onGround = verticalCollision && requestedY < 0.0;
        if (verticalCollision) vy = 0.0; // ordinary Block.onLanded

        // Loaded-column branch and exact widened float damping constants.
        vy -= .08;
        vy *= .9800000190734863;
        vx *= (double) friction;
        vz *= (double) friction;
        return new State(x, y, z, vx, vy, vz, box, onGround,
                horizontalCollision, verticalCollision, horizontalCollision || verticalCollision, false, false, false);
    }

    private static void requireSupported(State state, Environment environment) {
        if (state.noClip || state.isInWeb || state.sneaking)
            throw new IllegalArgumentException("Unsupported noClip, web, or sneak state");
        if (environment.water || environment.lava || environment.ladder)
            throw new IllegalArgumentException("Unsupported liquid or ladder branch");
        if (!environment.loadedColumnBefore || !environment.loadedColumnAfter)
            throw new IllegalArgumentException("Unloaded-column gravity branch is unsupported");
        if (environment.disk==null && state.box.minY < environment.floorTopY)
            throw new IllegalArgumentException("Initial box penetrates declared floor");
    }

    private static void finite(double... values) {
        for (double value : values)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite state");
    }
}
