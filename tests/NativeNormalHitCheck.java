import java.util.Random;
import lab.DamageBenefits;
import lab.R;

/** Unmodified 1.8.9 mitigation, actual armor subsets/enchants, and native float HP arithmetic. */
public final class NativeNormalHitCheck {
    static final class Body extends pr {
        zx[] equipment=new zx[4];int resistance=-1;
        Body(){super(null);}
        public zx bA(){return null;}
        public zx p(int i){return equipment[i];}
        public void c(int i,zx item){equipment[i]=item;}
        public zx[] as(){return equipment==null?new zx[4]:equipment;}
        public boolean a(pe effect){return effect==pe.m?resistance>=0:super.a(effect);}
        public pf b(pe effect){return effect==pe.m&&resistance>=0?new pf(11,100,resistance):super.b(effect);}
        float reduce(float raw){return super.c(SOURCE,super.b(SOURCE,raw));}
    }
    static final ow SOURCE=new ow("player");
    static Random rng;
    static double loss(Body body,float applied,boolean block,float hp,float absorption,long seed){
        rng.setSeed(seed);float damage=body.reduce(block?(1+applied)*.5f:applied);
        body.i(hp);body.i(body.bn()-Math.max(0,damage-absorption));return (double)hp-body.bn();
    }
    static void near(double a,double b){if(Math.abs(a-b)>1e-6)throw new AssertionError(a+" != native "+b);}
    public static void main(String[] args){
        R.call(R.cls("net.minecraft.init.Bootstrap"),"register");
        rng=(Random)R.get(R.cls("net.minecraft.enchantment.EnchantmentHelper"),"a");
        // Find an actual vanilla equipment combination for every armor total.
        String[] materials={"", "leather", "golden", "chainmail", "iron", "diamond"};
        String[] slots={"boots","leggings","chestplate","helmet"};
        java.util.Map<Integer,String[]> kits=new java.util.TreeMap<>();
        for(int combo=0;combo<1296;combo++){
            Body body=new Body();String[] names=new String[4];int remaining=combo;
            for(int i=0;i<4;i++){
                int material=remaining%6;remaining/=6;
                if(material>0){names[i]=materials[material]+"_"+slots[i];body.equipment[i]=(zx)R.construct("net.minecraft.item.ItemStack",R.get(R.cls("net.minecraft.init.Items"),names[i]));}
            }
            kits.putIfAbsent(body.br(),names);
        }
        if(kits.size()!=21)throw new AssertionError("Expected real kits with all armor values 0–20: "+kits.keySet());
        int cases=0;java.util.Set<Integer> armorValues=new java.util.TreeSet<>();
        for(String[] items:kits.values())for(boolean prot:new boolean[]{false,true}){
            Body body=new Body();
            for(int i=0;i<4;i++)if(items[i]!=null){
                zx stack=(zx)R.construct("net.minecraft.item.ItemStack",R.get(R.cls("net.minecraft.init.Items"),items[i]));
                if(prot)R.call(stack,"setTagCompound",R.call(R.cls("net.minecraft.nbt.JsonToNBT"),"getTagFromJson","{ench:[{id:0s,lvl:4s}]}"));
                body.equipment[i]=stack;
            }
            int armor=body.br();armorValues.add(armor);
            for(int resistance:new int[]{-1,0,1,4}){
                body.resistance=resistance;
                for(long seed=0;seed<10;seed++)for(float raw:new float[]{5,8}){
                    rng.setSeed(seed);int roll=ack.a(body.as(),SOURCE);
                    rng.setSeed(seed);double nativeUnit=body.reduce(raw);
                    double reference=DamageBenefits.normalHitDamage(raw,armor,resistance,roll);
                    if(reference!=nativeUnit)throw new AssertionError("Normal reference differs from native float mitigation");
                    for(float applied:new float[]{raw,raw*1.5f,raw*.5f})for(float hp:new float[]{20,.1f})for(float absorption:new float[]{0,.5f}){
                        double saved=Math.max(0,loss(body,applied,false,hp,absorption,seed)-loss(body,applied,true,hp,absorption,seed));
                        double modelSaved=Math.max(0,DamageBenefits.loss(applied,false,armor,resistance,roll,hp,absorption)-DamageBenefits.loss(applied,true,armor,resistance,roll,hp,absorption));
                        near(modelSaved,saved);
                        Double equivalent=DamageBenefits.normalHits(modelSaved,raw,armor,resistance,roll);
                        near(equivalent,nativeUnit>0?saved/nativeUnit:0);cases++;
                    }
                }
            }
        }
        System.out.println("PASS "+cases+" native normal-hit equivalent cases; armor points "+armorValues+"; wood/diamond, Protection IV, Resistance, crit/partial upgrades, absorption and HP caps");
    }
}
