package sword;

import io.netty.buffer.Unpooled;
import java.util.Random;
import net.minecraft.core.Holder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.*;

/** Independent component checks calling official classes from the pinned client jar.
 * The actor replaces only the level-dependent attribute, motion storage and grounding.
 * The actual LivingEntity.knockback body and wire codec run unchanged. */
public final class VanillaParity {
    static final class Target extends ArmorStand {
        Vec3 velocity; boolean grounded;
        Target(){super(null,null);}
        @Override public double getAttributeValue(Holder<Attribute> attribute){return 0;}
        @Override public Vec3 getDeltaMovement(){return velocity;}
        @Override public void setDeltaMovement(Vec3 value){velocity=value;}
        @Override public boolean onGround(){return grounded;}
    }
    static void equal(String name,double a,double b){
        if(Double.doubleToLongBits(a)!=Double.doubleToLongBits(b))throw new AssertionError(name+" "+a+" "+b);
    }
    static void velocity(String name, FlatPhysics.Impulse result,Vec3 nativeValue){
        equal(name+" X",result.x,nativeValue.x);equal(name+" Y",result.y,nativeValue.y);equal(name+" Z",result.z,nativeValue.z);
    }
    public static void main(String[] args)throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var unsafeType=Class.forName("sun.misc.Unsafe");var field=unsafeType.getDeclaredField("theUnsafe");field.setAccessible(true);
        var target=(Target)unsafeType.getMethod("allocateInstance",Class.class).invoke(field.get(null),Target.class);
        var source=new DamageSource(null);
        var modifier=LocalPlayer.class.getDeclaredMethod("modifyInputSpeedForSquareMovement",Vec2.class);modifier.setAccessible(true);
        var rotate=Entity.class.getDeclaredMethod("getInputVector",Vec3.class,float.class,float.class);rotate.setAccessible(true);
        var random=new Random(721994);var wire=Unpooled.buffer(32);
        int count=16384;
        try{
            for(int i=0;i<65536;i++){
                double rad=(i+.25)/FlatPhysics.SCALE;equal("sin",FlatPhysics.sin(rad),Mth.sin(rad));equal("cos",FlatPhysics.cos(rad),Mth.cos(rad));
            }
            for(int i=0;i<count;i++){
                float yaw=random.nextFloat(-540,540);double dx=random.nextDouble(-4,4),dz=random.nextDouble(-4,4);
                boolean fresh=(i&1)!=0,boost=(i&2)!=0;target.grounded=(i&4)!=0;
                target.velocity=new Vec3(random.nextDouble(-2,2),random.nextDouble(-1,1),random.nextDouble(-2,2));
                var impulse=new FlatPhysics.Impulse();var v=target.velocity;
                impulse.hit(v.x,v.y,v.z,target.grounded,dx,dz,yaw,fresh,boost);
                if(fresh)target.knockback(.4f,-dx,-dz,source,7f);
                if(boost)target.knockback(.5,Mth.sin(yaw*.017453292f),-Mth.cos(yaw*.017453292f),source,7f,true);
                velocity("impulse",impulse,target.velocity);
                wire.clear();Vec3.LP_STREAM_CODEC.encode(wire,target.velocity);impulse.wire();
                velocity("wire",impulse,Vec3.LP_STREAM_CODEC.decode(wire));
                float damage=random.nextFloat(0,11);
                equal("diamond armor",FlatPhysics.armored(damage),CombatRules.getDamageAfterAbsorb(target,damage,source,20,8));
                int forward=i%3-1,side=(i/3)%3-1;
                var stick=(Vec2)modifier.invoke(null,new Vec2(side,forward).normalized().scale(.98f));
                var p=new FlatPhysics.Motion();p.yaw=yaw;p.ground=(i&4)!=0;p.vy=0;boolean sprint=(i&8)!=0;
                float speed=p.ground?(sprint?(float)(.1f*(1.+.3f)):.1f)*(.21600002f/(.6f*.6f*.6f)):(sprint?.025999999f:.02f);
                var direction=(Vec3)rotate.invoke(null,new Vec3(stick.x,0,stick.y),speed,yaw);
                p.travel(forward,side,sprint);
                equal("keyboard X",p.x,direction.x+0.);equal("keyboard Z",p.z,direction.z+0.);
            }
        }finally{wire.release();}
        System.out.println("65536 sine/cosine index pairs and "+count+" keyboard, knockback, wire and armor cases matched at bit precision.");
    }
}
