import practice.core.bow.BowBallistics;
import practice.core.bow.BowBallistics.Shot;
import practice.core.bow.BowHitChance;
import practice.core.bow.BowMotion;
import practice.core.bow.BowMotion.Track;
import practice.core.bow.BridgeMotion;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

public final class ProbabilityStudy {
    private static final int[] HORIZONS = BowMotion.HORIZONS;
    private static final int[] RANGES = {10,18,28,40,52};
    private static final int STEPS = BowHitChance.CALIBRATION_STEPS;
    private static final int MAX = BowBallistics.MAX_TICKS;

    private final BowMotion base;
    private final BridgeMotion bridge;

    ProbabilityStudy(Path base, Path bridge) throws IOException {
        this.base = BowMotion.read(Files.newInputStream(base));
        this.bridge = BridgeMotion.read(Files.newInputStream(bridge));
    }

    private interface Windows {
        void accept(Track track, double[][] forecast, boolean build, float[][] pos, int issue, long seed);
    }

    private void visit(Path file, int first, int last, int stride, Windows callback) throws IOException {
        Study.read(file,(game,id,ticks,xyz,flags) -> {
            if (game < first || game > last) return;
            int[] future = Study.contiguous(ticks,xyz);
            Track track = new Track();
            for (int i = 0; i < ticks.length; i++) {
                track.add(ticks[i],xyz[i][0],xyz[i][1],xyz[i][2],flags[i]);
                if (future[i]<MAX || track.length() < 8 || (ticks[i]+id)%stride != 0) continue;
                double[][] common = base.path(track,BowMotion.Mode.ADAPTIVE,MAX);
                double[][] forecast = bridge.apply(track,common);
                callback.accept(track,forecast,forecast!=common,xyz,i,
                        ticks[i]*8359L+id*6007L+game*9133L);
            }
        });
    }

    private static final class Bucket {
        final Random random;
        final float[][] residuals = new float[BowHitChance.SAMPLES][3];
        int seen;

        Bucket(long key) { random = new Random(key); }

        void add(double x, double y, double z) {
            int k = seen < residuals.length ? seen : random.nextInt(seen+1);
            seen++;
            if (k >= residuals.length) return;
            residuals[k][0]=(float)x; residuals[k][1]=(float)y; residuals[k][2]=(float)z;
        }
    }

    private void fit(Path data, Path output) throws IOException {
        Bucket[][] errors = new Bucket[BowHitChance.GROUPS][HORIZONS.length];
        for (int g=0; g<errors.length; g++) for (int h=0;h<HORIZONS.length;h++)
            errors[g][h]=new Bucket(60581+g*109+h*239);
        visit(data,1,6,7,(track,forecast,build,pos,i,seed) -> {
            int group = BowHitChance.group(track,build);
            double angle = BowHitChance.heading(track), c=Math.cos(angle),s=Math.sin(angle);
            for (int h=0;h<HORIZONS.length;h++) {
                int t=HORIZONS[h];
                double dx=pos[i+t][0]-forecast[t][0],dy=pos[i+t][1]-forecast[t][1],dz=pos[i+t][2]-forecast[t][2];
                errors[group][h].add(c*dx+s*dz,dy,-s*dx+c*dz);
            }
        });
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(output))) {
            out.writeInt(0x42484331);
            for (int g=0;g<errors.length;g++) {
                System.err.println("Residual group "+g+": "+errors[g][0].seen+" samples");
                for (int h=0;h<HORIZONS.length;h++) {
                    Bucket b=errors[g][h];
                    if (b.seen==0) b=errors[g^1][h];
                    if (b.seen==0) b=errors[g&3][h];
                    if (b.seen==0) b=errors[2][h];
                    for (int k=0; k<BowHitChance.SAMPLES;k++)
                        for (float value : b.residuals[k%Math.min(b.seen,BowHitChance.SAMPLES)]) out.writeFloat(value);
                }
            }
            for (int k=0; k<STEPS;k++) out.writeDouble(k/(double)(STEPS-1));
        }
    }

    private static final class Scores {
        int count, positives;
        double brier, sum;
        void add(double p, boolean hit) {
            count++;
            double difference = p-(hit ? 1 : 0);
            brier += difference*difference;
            sum += p;
            if (hit) positives++;
        }
    }

    private Scores[] collect(Path data, BowHitChance model, int first, int last, boolean raw) throws IOException {
        Scores[] bins = new Scores[STEPS];
        Arrays.setAll(bins,i->new Scores());
        visit(data,first,last,17,(track,forecast,build,pos,i,seed) -> {
            Random rng=new Random(seed);
            double angle=rng.nextDouble()*2*Math.PI;
            double range=RANGES[rng.nextInt(RANGES.length)];
            double x=pos[i][0]+range*Math.sin(angle),y=pos[i][1]+1.62,z=pos[i][2]+range*Math.cos(angle);
            Shot perfect=BowBallistics.intercept(x,y,z,3,forecast,.9);
            if (perfect==null) return;
            double sigma=(rng.nextBoolean() ? .7 : 2.3);
            Shot aimed=BowBallistics.fromAim(x,y,z,3,(float)(perfect.yaw+rng.nextGaussian()*sigma),
                        (float)(perfect.pitch+rng.nextGaussian()*sigma));
            double p=raw ? model.rawEstimate(track,build,aimed,perfect.time,MAX,forecast)
                    : model.estimate(track,build,aimed,perfect.time,MAX,forecast);
            bins[(int)Math.round(p*(STEPS-1))].add(p,Study.hit(aimed,pos,i,seed^649433L));
        });
        return bins;
    }

    private void calibrate(Path data, Path model) throws IOException {
        Scores[] scores = collect(data,BowHitChance.read(Files.newInputStream(model)),7,8,true);
        double[] total=new double[STEPS], weight=new double[STEPS];
        int[] size=new int[STEPS];
        int groups=0;
        for (int i=0;i<STEPS;i++) {
            total[groups]=scores[i].positives + 5*i/(double)(STEPS-1);
            weight[groups]=5+scores[i].count;
            size[groups]=1;
            groups++;
            while (groups>1 && total[groups-2]/weight[groups-2] > total[groups-1]/weight[groups-1]) {
                total[groups-2]+=total[groups-1];
                weight[groups-2]+=weight[groups-1];
                size[groups-2]+=size[groups-1];
                groups--;
            }
        }
        byte[] bytes=Files.readAllBytes(model);
        ByteBuffer dst=ByteBuffer.wrap(bytes);
        dst.position(bytes.length-8*STEPS);
        for (int g=0;g<groups;g++) for (int k=0;k<size[g];k++) dst.putDouble(total[g]/weight[g]);
        Files.write(model,bytes);
    }

    public static void main(String[] args) throws IOException {
        Path data=Paths.get(args[1]), model=Paths.get(args[4]);
        ProbabilityStudy study=new ProbabilityStudy(Paths.get(args[2]),Paths.get(args[3]));
        if ("fit".equals(args[0])) {
            study.fit(data,model);
            study.calibrate(data,model);
        } else if ("score".equals(args[0])) {
            BowHitChance chance=BowHitChance.read(Files.newInputStream(model));
            System.out.println("method,bucket,n,mean_probability,hit_rate,brier");
            for (boolean raw : new boolean[]{true,false}) {
                Scores[] scores=study.collect(data,chance,9,12,raw);
                for (int k=0; k<scores.length; k++) {
                    Scores b=scores[k];
                    if (b.count==0) continue;
                    System.out.printf(Locale.ROOT,"%s,%d,%d,%.5f,%.5f,%.5f%n",raw?"raw":"calibrated",10*k,
                            b.count,b.sum/b.count,b.positives/(double)b.count,b.brier/b.count);
                }
            }
        } else throw new IllegalArgumentException("Choose fit or score");
    }
}
