package sword;

import java.nio.file.*;
import java.io.*;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.Vec3;

public final class Referee {
    static Path dir;
    static int tick=-1;
    static String[] round;
    static long now(){return System.getProperty("sword.clock")==null?System.currentTimeMillis():Lockstep.time()+15;}
    static PrintWriter trace,contacts;
    static boolean boostedAttack;
    static int immunity;
    static float charge;
    static net.minecraft.world.entity.Entity attackTarget;
    static Vec3 beforeVelocity,packetVelocity;
    static boolean targetGround;
    static int requestedHits,streak,branchSeat;
    static boolean branched;
    static Path spool;
    public static void beforeAttack(net.minecraft.world.entity.player.Player p,net.minecraft.world.entity.Entity target){
        immunity=target instanceof net.minecraft.world.entity.LivingEntity living?living.damageCooldownTime:0;charge=p.getAttackStrengthScale(.5f);
        boostedAttack=p.isSprinting()&&p.getAttackStrengthScale(.5f)>.9f;
        attackTarget=target;beforeVelocity=target.getDeltaMovement();targetGround=target.onGround();packetVelocity=null;
    }
    public static void motionPacket(net.minecraft.world.entity.Entity target){
        if(target==attackTarget)packetVelocity=target.getDeltaMovement();
    }
    public static void contact(net.minecraft.world.entity.player.Player attacker,net.minecraft.world.entity.Entity target,
                               boolean critical,boolean sweep,boolean strong,boolean special){
        if(round!=null&&contacts!=null&&target instanceof ServerPlayer defender){
            Vec3 packet=packetVelocity==null?new Vec3(Double.NaN,Double.NaN,Double.NaN):packetVelocity;
            contacts.printf(Locale.ROOT,"%d\t%s\t%s\t%b\t%b\t%b\t%b\t%s\t%d\t%d\t%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%b\t%s\t%s\t%s%n",
                tick,attacker.getGameProfile().name(),defender.getGameProfile().name(),critical,sweep,strong,boostedAttack,defender.getHealth(),defender.hurtTime,immunity,charge,now(),
                beforeVelocity.x,beforeVelocity.y,beforeVelocity.z,packet.x,packet.y,packet.z,targetGround,defender.getX()-attacker.getX(),defender.getZ()-attacker.getZ(),attacker.getYRot());
            if(requestedHits>0&&!branched)checkLandmark(attacker,defender);
        }
        attackTarget=null;
    }
    static void checkLandmark(net.minecraft.world.entity.player.Player attacker,ServerPlayer defender){
        try{
            Path first=spool.resolve(round[0]+"-defender-press");
            if(!Files.exists(first))return;
            if(!attacker.getGameProfile().name().equals(round[1+branchSeat])){streak=0;return;}
            if(immunity>10||++streak<requestedHits)return;
            long when=now();
            double distance=attacker.position().distanceTo(defender.position());
            write(round[0]+"-landmark.tsv","tick\tepochMs\tdefenderFirstPressMs\tdistance\townHP\topponentHP\townGround\topponentGround\n"+
                String.format(Locale.ROOT,"%d\t%d\t%s\t%.5f\t%.4f\t%.4f\t%b\t%b",tick,when,Files.readString(first),
                    distance,attacker.getHealth(),defender.getHealth(),attacker.onGround(),defender.onGround()));
            Files.createFile(spool.resolve(round[0]+"-branch"));branched=true;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    static boolean initialized;
    static Vec3 spawnA, spawnB;
    static float facingA, facingB;
    static long releaseTime;
    static void cmd(MinecraftServer s,String c){s.getCommands().performPrefixedCommand(s.createCommandSourceStack(),c);}
    static void teleportToSpawn(MinecraftServer s,ServerPlayer p,double x,double z,double yaw){
        String name=p.getGameProfile().name();
        cmd(s,String.format(Locale.ROOT,"tp %s %.4f 101 %.4f %.2f 0",name,x,z,yaw));
    }
    static String settleSpawn(MinecraftServer s, ServerPlayer p, Vec3 spawn, float yaw) {
        double drift=p.position().distanceTo(spawn);
        boolean correct=drift>0 && now()<releaseTime;
        if(correct){
            teleportToSpawn(s,p,spawn.x,spawn.z,yaw);
            p.setDeltaMovement(Vec3.ZERO);
            p.connection.send(new ClientboundSetEntityMotionPacket(p));
        }
        return p.getGameProfile().name()+"\t"+spawn.x+"\t"+spawn.y+"\t"+spawn.z+"\t"+drift+"\t"+correct+"\n";
    }
    static void prepare(MinecraftServer s,ServerPlayer p,double x,double z,double yaw){
        teleportToSpawn(s,p,x,z,yaw);
        p.getInventory().clearContent();
        p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));
        p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
        p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));
        p.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));
        p.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));
        p.removeAllEffects();p.setHealth(20);p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(20);
        p.setInvulnerableTime(0);p.damageCooldownTime=0;p.hurtTime=0;p.setDeltaMovement(Vec3.ZERO);p.resetFallDistance();p.setSprinting(false);
    }
    static void write(String file,String content)throws IOException{
        var root=(file.equals("A")||file.equals("B"))?Path.of(System.getProperty("sword.spool")):dir;
        var p=root.resolve(file);var tmp=root.resolve(file+".tmp");Files.writeString(tmp,content);
        Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public static void tick(MinecraftServer s){
        String root=System.getProperty("sword.run");if(root==null)return;
        try{
            dir=Path.of(root);
            if(!initialized){
                spool=Path.of(System.getProperty("sword.spool"));
                cmd(s,"forceload add -16 -16 16 16");
                cmd(s,"fill -13 100 -13 12 100 12 minecraft:stone");
                cmd(s,"fill -13 101 -13 -13 107 12 minecraft:barrier");
                cmd(s,"fill 12 101 -13 12 107 12 minecraft:barrier");
                cmd(s,"fill -13 101 -13 12 107 -13 minecraft:barrier");
                cmd(s,"fill -13 101 12 12 107 12 minecraft:barrier");
                cmd(s,"gamerule minecraft:keep_inventory true");
                cmd(s,"setworldspawn 0 101 0");cmd(s,"gamerule minecraft:natural_health_regeneration false");
                cmd(s,"gamerule minecraft:immediate_respawn true");cmd(s,"gamerule minecraft:spawn_mobs false");
                int speed=Integer.getInteger("sword.speed",1);
                cmd(s,"tick rate "+20*speed);initialized=true;write("ready","ready");return;
            }
            if(round==null){
                if(System.getProperty("sword.clock")!=null&&(!Lockstep.active||Lockstep.tick<48))return;
                Path req=dir.resolve("request");if(!Files.exists(req))return;
                var fields=Files.readString(req).strip().split("\t");
                var a=s.getPlayerList().getPlayerByName(fields[1]);var b=s.getPlayerList().getPlayerByName(fields[2]);
                if(a==null||b==null||!a.isAlive()||!b.isAlive())return;
                if(Lockstep.ENABLED&&(!a.connection.hasClientLoaded()||!b.connection.hasClientLoaded()))return;
                double angle=Double.parseDouble(fields[3]),gap=Double.parseDouble(fields[4]);
                double x=Math.cos(angle)*gap/2,z=Math.sin(angle)*gap/2,yaw=Math.toDegrees(angle)-90;
                prepare(s,a,-x,-z,yaw);prepare(s,b,x,z,yaw+180);
                spawnA=a.position();spawnB=b.position();facingA=a.getYRot();facingB=b.getYRot();
                requestedHits=fields.length>8?Integer.parseInt(fields[8]):0;
                branchSeat=requestedHits>0?Integer.parseInt(fields[7]):-1;
                branched=false;streak=0;
                long start=(System.getProperty("sword.clock")==null?now():Lockstep.time())+1600/Integer.getInteger("sword.speed",1);
                releaseTime=start;
                write("A",fields[0]+"\t"+fields[2]+"\t"+start+"\t"+fields[5]+(requestedHits==0?"":branchSeat==0?"\topening":"\tdefender"));
                write("B",fields[0]+"\t"+fields[1]+"\t"+start+"\t"+fields[6]+(requestedHits==0?"":branchSeat==1?"\topening":"\tdefender"));
                trace=new PrintWriter(Files.newBufferedWriter(dir.resolve(fields[0]+"-server.tsv")));
                trace.println("tick\thpA\thpB\tax\tay\taz\tbx\tby\tbz\tchargeA\tchargeB\tsprintA\tsprintB\thurtA\thurtB\tepochMs\tdamageCooldownA\tdamageCooldownB\taxVelocity\tayVelocity\tazVelocity\tbxVelocity\tbyVelocity\tbzVelocity\taGround\tbGround");
                contacts=new PrintWriter(Files.newBufferedWriter(dir.resolve(fields[0]+"-contacts.tsv")));
                contacts.println("tick\tattacker\tdefender\tcritical\tsweep\tstrong\tboosted\tdefenderHP\thurt\tpreDamageCooldown\tcharge\tepochMs\tserverVX\tserverVY\tserverVZ\tpacketVX\tpacketVY\tpacketVZ\tdefenderGround\tdx\tdz\tattackerYaw");
                round=fields;tick=-32;Files.delete(req);return;
            }
            ServerPlayer a=s.getPlayerList().getPlayerByName(round[1]),b=s.getPlayerList().getPlayerByName(round[2]);
            if(a==null||b==null){throw new IOException("Player left during "+round[0]);}
            // During the neutral countdown, stale motion after the teleport can shift
            // a free-running client. Re-seat it after its movement reports settle.
            if(!Lockstep.ENABLED&&tick==-16&&!round[5].equals("human")&&!round[6].equals("human")){
                write(round[0]+"-countdown.tsv","name\tx\ty\tz\tdriftBlocks\tcorrected\n"+
                      settleSpawn(s,a,spawnA,facingA)+settleSpawn(s,b,spawnB,facingB));
            }
            if(Lockstep.ENABLED&&tick==-9){
                long calendar=Double.doubleToLongBits(Double.parseDouble(round[3]));
                int phaseA=(int)(calendar&1),phaseB=(int)((calendar>>>1)&1);
                Lockstep.alignTracker(a,b,phaseA);Lockstep.alignTracker(b,a,phaseB);
                write(round[0]+"-clock.tsv","phaseA\tphaseB\n"+phaseA+"\t"+phaseB);
            }
            float ha=a.getHealth(),hb=b.getHealth();
            trace.printf(Locale.ROOT,"%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%b\t%b\t%d\t%d\t%d\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%b\t%b%n",tick,ha,hb,a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ(),a.getAttackStrengthScale(0),b.getAttackStrengthScale(0),a.isSprinting(),b.isSprinting(),a.hurtTime,b.hurtTime,now(),a.damageCooldownTime,b.damageCooldownTime,a.getDeltaMovement().x,a.getDeltaMovement().y,a.getDeltaMovement().z,b.getDeltaMovement().x,b.getDeltaMovement().y,b.getDeltaMovement().z,a.onGround(),b.onGround());
            if(ha<=0||hb<=0||++tick>=Model.LIMIT){
                trace.close();contacts.close();write("A","");write("B","");write(round[0]+".result",ha+"\t"+hb+"\t"+tick);round=null;
            }
        }catch(Exception e){throw new IllegalStateException("Sword referee",e);}
    }
}
