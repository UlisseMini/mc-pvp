import java.io.*;
import java.util.*;
import lab.DuelNetwork;
import lab.SumoInputs;

/** At a fixed observed state, replay each intervention with the actual previously applied actions. */
final class InputDecisionTrace {
    public static void main(String[] args)throws Exception{
        var in=new BufferedReader(new InputStreamReader(System.in));var base=DuelNetwork.decode(in.readLine());
        var names=new ArrayList<String>();var variants=new ArrayList<DuelNetwork>();
        var w=base.weights();int last=w.length-1,mask=(int)w[last];
        String[] labels=mask==0?new String[]{"own-ping","own-motion","foe-motion","hurt"}:new String[]{"motion","edge","timing"};
        int[] flags=mask==0?new int[]{SumoInputs.BLIND,SumoInputs.NO_SELF,SumoInputs.NO_FOE,SumoInputs.NO_HURT}:new int[]{SumoInputs.MOTION,SumoInputs.EDGE,SumoInputs.TIMING};
        for(int i=0;i<flags.length;i++){
            var v=w.clone();v[last]=mask==0?mask|flags[i]:mask&~flags[i];
            variants.add(new DuelNetwork(base.family(),v));names.add(labels[i]);
        }
        if(mask==0){
            String[] axes={"radial","sideways","vertical"};
            for(int axis=0;axis<axes.length;axis++){
                var v=w.clone();
                for(int unit=0;unit<DuelNetwork.width(base.family());unit++)
                    v[unit*(SumoInputs.COUNT+1)+1+2+axis]=0;
                variants.add(new DuelNetwork(base.family(),v));names.add(axes[axis]);
            }
        }
        Map<String,int[]> counts=new LinkedHashMap<>();String l;
        while((l=in.readLine())!=null){
            var p=l.split(" ");double[] f=new double[16];for(int i=0;i<16;i++)f[i]=Double.parseDouble(p[i]);
            int applied=Integer.parseInt(p[16]);boolean active=p[17].equals("1");
            int a=apply(base,f,applied);if(a!=applied)throw new AssertionError("Original native head disagrees");
            boolean ownRecent=f[11]>=0&&f[11]<150,foeRecent=f[12]>=0&&f[12]<150;
            String phase=ownRecent?(foeRecent?"both_recent":"own_recent"):(foeRecent?"opponent_recent":"neither_recent");
            for(int i=0;i<variants.size();i++){
                int b=apply(variants.get(i),f,applied);if(!active)continue;
                count(counts,names.get(i)+",all",a,b);
                count(counts,names.get(i)+","+phase,a,b);
            }
        }
        for(var row:counts.entrySet())System.out.println(row.getKey()+","+String.join(",",Arrays.stream(row.getValue()).mapToObj(Integer::toString).toList()));
    }
    static void count(Map<String,int[]> counts,String group,int a,int b){
        int[] c=counts.computeIfAbsent(group,k->new int[6]);
        c[0]++;if(a!=b)c[1]++;if(DuelNetwork.key(a)!=DuelNetwork.key(b))c[2]++;
        if(DuelNetwork.side(a)!=DuelNetwork.side(b))c[3]++;
        if(DuelNetwork.click(a)!=DuelNetwork.click(b))c[4]++;
        if(DuelNetwork.jump(a)!=DuelNetwork.jump(b))c[5]++;
    }
    static int apply(DuelNetwork n,double[] v,int applied){
        n.localTiming(v[14],v[15]<0?Double.POSITIVE_INFINITY:v[15]);
        int action=n.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9]!=0,v[10]!=0,
            v[11]<0?Double.POSITIVE_INFINITY:v[11],v[12]<0?Double.POSITIVE_INFINITY:v[12],v[13]);
        n.applied(applied);return action;
    }
}
