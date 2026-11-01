import java.util.Random;
import lab.DamageBenefits;
import lab.R;

/** Paired native armor/Protection calculations, resetting only this test JVM's RNG between alternatives. */
public final class NativeDamageBenefitsCheck {
    static final class Body extends pr {
        zx[] equipment=new zx[4];
        Body(){super(null);}
        public zx bA(){return null;}
        public zx p(int i){return equipment[i];}
        public void c(int i,zx item){equipment[i]=item;}
        public zx[] as(){return equipment==null?new zx[4]:equipment;}
        float reduce(ow source,float amount){return super.c(source,super.b(source,amount));}
    }
    static Random enchantRandom;
    static final ow SOURCE=new ow("player");
    static double nativeLoss(Body body,float applied,boolean block,float hp,float absorption,long seed){
        if(applied<=0)return 0;
        enchantRandom.setSeed(seed);
        float amount=block?(1+applied)*.5f:applied;
        amount=body.reduce(SOURCE,amount);
        body.i(hp);body.i(body.bn()-Math.max(0,amount-absorption));
        return (double)hp-body.bn();
    }
    static void near(double actual,double expected){if(Math.abs(actual-expected)>1e-6)throw new AssertionError(actual+" vs native "+expected);}
    public static void main(String[] args){
        R.call(R.cls("net.minecraft.init.Bootstrap"),"register");
        enchantRandom=(Random)R.get(R.cls("net.minecraft.enchantment.EnchantmentHelper"),"a");
        int pairs=0;
        for(int kit=0;kit<3;kit++){
            Body body=new Body();
            if(kit>0){int i=0;for(String item:new String[]{"diamond_boots","diamond_leggings","diamond_chestplate","diamond_helmet"}){
                zx stack=(zx)R.construct("net.minecraft.item.ItemStack",R.get(R.cls("net.minecraft.init.Items"),item));
                if(kit==2)R.call(stack,"setTagCompound",R.call(R.cls("net.minecraft.nbt.JsonToNBT"),"getTagFromJson","{ench:[{id:0s,lvl:4s}]}"));
                body.equipment[i++]=stack;
            }}
            for(long seed=0;seed<100;seed++)for(boolean block:new boolean[]{false,true})for(float hp:new float[]{20,.1f})for(float absorption:new float[]{0,.5f}){
                enchantRandom.setSeed(seed);int roll=ack.a(body.as(),SOURCE);int armor=body.br();
                // Raw ordinary and critical values, plus an immunity-window crit-only upgrade.
                for(float[] pair:new float[][]{{8,12},{5,7.5f},{1,1.5f},{0,4}}){
                    double normal=nativeLoss(body,pair[0],block,hp,absorption,seed),crit=nativeLoss(body,pair[1],block,hp,absorption,seed);
                    double modelNormal=DamageBenefits.loss(pair[0],block,armor,-1,roll,hp,absorption);
                    double modelCrit=DamageBenefits.loss(pair[1],block,armor,-1,roll,hp,absorption);
                    near(modelNormal,normal);near(modelCrit,crit);near(modelCrit-modelNormal,crit-normal);
                    double noBlock=nativeLoss(body,pair[1],false,hp,absorption,seed);
                    near(DamageBenefits.loss(pair[1],false,armor,-1,roll,hp,absorption)-modelCrit,noBlock-crit);
                    pairs++;
                }
            }
        }
        System.out.println("PASS "+pairs+" paired native damage cases: no armor/diamond/Protection IV, same RNG, crit/block overlap, immunity, absorption and lethal caps");
    }
}
