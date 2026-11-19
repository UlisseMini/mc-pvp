import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lab.DuelNetwork;
import lab.PairedExperts;

/** Fit signed-score advantages, with each opponent contributing equal total weight. */
final class ContinuationFit {
    record Sample(String opponent,double[] x,double delta){}
    static double dot(double[] left,double[] right){
        double v=0;for(int i=0;i<left.length;i++)v+=left[i]*right[i];return v;
    }
    static double[] solve(double[][] data,double[] rhs,double penalty){
        int k=rhs.length;double[][] lower=new double[k][k];
        for(int i=0;i<k;i++)for(int j=0;j<=i;j++){
            double v=data[i][j]+(i==j?(i==0?1e-9:penalty):0);
            for(int m=0;m<j;m++)v-=lower[i][m]*lower[j][m];
            if(i==j && v<=0)throw new IllegalArgumentException("Covariance is not positive definite");
            lower[i][j]=i==j?Math.sqrt(v):v/lower[j][j];
        }
        double[] result=rhs.clone();
        for(int i=0;i<k;i++){
            for(int j=0;j<i;j++)result[i]-=lower[i][j]*result[j];
            result[i]/=lower[i][i];
        }
        for(int i=k-1;i>=0;i--){
            for(int j=i+1;j<k;j++)result[i]-=lower[j][i]*result[j];
            result[i]/=lower[i][i];
        }
        return result;
    }
    static double[] unit(double[] coefficients,double[][] covariance){
        double variance=0;
        for(int i=0;i<coefficients.length;i++)variance+=coefficients[i]*dot(coefficients,covariance[i]);
        double rms=Math.sqrt(Math.max(1e-10,variance));double[] normalized=coefficients.clone();
        for(int i=0;i<normalized.length;i++)normalized[i]/=rms;
        return normalized;
    }
    public static void main(String[] args)throws Exception{
        var prior=DuelNetwork.decode(Files.readString(Path.of(args[0])).trim());Path out=Path.of(args[1]);Files.createDirectories(out);
        int k=PairedExperts.GATE;var rows=new ArrayList<Sample>();var by=new HashMap<String,Integer>();
        int first=2;var groups=new HashMap<String,String>();
        if(args[2].equals("--groups")){
            for(String line:Files.readAllLines(Path.of(args[3]))){
                String[] pair=line.split(" ");
                if(pair.length!=2||groups.put(pair[0],pair[1])!=null)throw new IllegalArgumentException("Invalid opponent league");
            }
            first=4;
        }
        for(int a=first;a<args.length;a++)try(var in=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(args[a])))))){
            String line;while((line=in.readLine())!=null){
                var r=line.split(" ");if(!r[6].equals("ok"))continue;
                double[] x=new double[k];if(r.length!=14+k)throw new IllegalArgumentException("Missing teaching feature");
                for(int i=0;i<k;i++)x[i]=Double.parseDouble(r[14+i]);
                rows.add(new Sample(r[1],x,Double.parseDouble(r[11])-Double.parseDouble(r[10])));
                by.merge(r[1],1,Integer::sum);
            }
        }
        if(rows.isEmpty())throw new IllegalArgumentException("Empty teacher set");
        var sizes=new HashMap<String,Integer>();
        for(String opponent:by.keySet()){
            if(!groups.isEmpty()&&!groups.containsKey(opponent))throw new IllegalArgumentException("Missing group for "+opponent);
            sizes.merge(groups.getOrDefault(opponent,"all"),1,Integer::sum);
        }
        double[][] cov=new double[k][k];double[] rhs=new double[k];
        for(var s:rows){
            double scale=1./((double)sizes.size()*sizes.get(groups.getOrDefault(s.opponent,"all"))*by.get(s.opponent));
            for(int i=0;i<k;i++){
                double v=scale*s.x[i];rhs[i]+=v*s.delta;
                for(int j=0;j<=i;j++)cov[i][j]+=v*s.x[j];
            }
        }
        for(int i=0;i<k;i++)for(int j=0;j<i;j++)cov[j][i]=cov[i][j];
        float[] weights=prior.weights();int offset=PairedExperts.HEADER;double[] original=new double[k];
        for(int i=0;i<k;i++)original[i]=weights[offset+i];
        original=unit(original,cov);
        double[] penalties={.0005,.01,.1},blends={.3,.65,1},deadbands={0,.25,.75};
        for(int p=0;p<penalties.length;p++){
            var newGate=unit(solve(cov,rhs,penalties[p]),cov);
            for(int b=0;b<blends.length;b++)for(int d=0;d<deadbands.length;d++){
                float[] w=weights.clone();w[7]=(float)deadbands[d];
                for(int i=0;i<k;i++)w[offset+i]=(float)((1-blends[b])*original[i]+blends[b]*newGate[i]);
                String name="fit-"+p+b+d;
                Files.writeString(out.resolve(name+".net"),DuelNetwork.encode(DuelNetwork.Family.PAIR,w)+"\n");
            }
        }
        System.out.println(rows.size()+" common decision states across "+by.size()+" opponents");
    }
}
