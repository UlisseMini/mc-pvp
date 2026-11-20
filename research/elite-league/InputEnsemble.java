import java.io.*;
import java.nio.file.*;
import java.util.*;
import lab.DuelNetwork;
import lab.SumoInputs;

/** Compile a vote over action scores into the same feedforward network that runs in Minecraft. */
final class InputEnsemble {
    static final Path DIR = InputStudy.DIR.resolve("ensemble");
    static final int STRIDE = SumoInputs.COUNT + 1;
    record Member(Arena.Fighter actor, float[] weights, double merit) {}

    static float[] canonical(Arena.Model model) {
        float[] weights = model.family() == DuelNetwork.Family.MLP
            ? InputStudy.warm(InputStudy.Arm.base8, model.weights(), 0) : model.weights().clone();
        int mask = (int) weights[weights.length - 1];
        if (mask != 0 && mask != SumoInputs.EDGE && mask != 112)
            throw new IllegalArgumentException("Unexpected member mask " + mask);
        for (int unit = 0; unit < 8; unit++) {
            for (int input = 22; input < SumoInputs.COUNT; input++) {
                int group = input < 29 ? SumoInputs.MOTION : input < 32 ? SumoInputs.EDGE : SumoInputs.TIMING;
                if ((mask & group) == 0) weights[unit * STRIDE + 1 + input] = 0;
            }
        }
        weights[weights.length - 1] = 112;
        return weights;
    }

    static Arena.Model pooled(List<Member> members) {
        if (members.size() != 4) throw new IllegalArgumentException("Expected four eight-unit networks");
        float[] merged = new float[DuelNetwork.size(DuelNetwork.Family.INPUT32)];
        for (int m = 0; m < 4; m++)
            System.arraycopy(members.get(m).weights(), 0, merged, 8 * m * STRIDE, 8 * STRIDE);
        for (int output = 0; output < 5; output++) {
            int src = 8 * STRIDE + 9 * output, dst = 32 * STRIDE + 33 * output;
            double bias = 0;
            for (int m = 0; m < 4; m++) {
                float[] v = members.get(m).weights();
                bias += .25 * v[src];
                for (int unit = 0; unit < 8; unit++) merged[dst + 1 + 8 * m + unit] = .25f * v[src + 1 + unit];
            }
            merged[dst] = (float) bias;
        }
        merged[merged.length - 1] = 112;
        return new Arena.Model(DuelNetwork.Family.INPUT32, merged);
    }

    static Arena.Model averaged(List<Member> members) {
        float[] result = new float[DuelNetwork.size(DuelNetwork.Family.INPUT8)];
        for (int i = 0; i < result.length - 1; i++) {
            double sum = 0;
            for (Member member : members) sum += member.weights()[i];
            result[i] = (float)(sum / members.size());
        }
        result[result.length - 1] = 112;
        return new Arena.Model(DuelNetwork.Family.INPUT8, result);
    }

    static double[] observe(DuelNetwork n, int t) {
        n.localTiming(47.5 * t, t % 5 == 0 ? 110 : 17.5);
        n.step(-4 + 3*Math.sin(.23*t),12+Math.abs(Math.sin(.53*t)),2*Math.sin(.12*t),
               4-3*Math.cos(.17*t),12+Math.abs(Math.sin(.43*t)),2*Math.cos(.23*t),
               .4*Math.sin(t),.7*Math.cos(t),.3*Math.sin(2*t),t%3==0,t%2==0,
               (t*29)%450,(t*37)%671,(t*51)%141);
        n.applied(DuelNetwork.pack(t%4,t%3-1,t%2==0,t%3==0));
        return n.scores();
    }

    static double verify(List<Member> members, Arena.Model model) {
        var compiled = new DuelNetwork(model.family(), model.weights());
        var raw = members.stream().map(m -> new DuelNetwork(m.actor().model().family(), m.actor().model().weights())).toList();
        double maxError = 0;
        for (int t = 0; t < 256; t++) {
            double[] actual = observe(compiled,t), expected = new double[5];
            for (var n : raw) {
                double[] logits = observe(n,t);
                for (int k=0; k<5; k++) expected[k] += .25*logits[k];
            }
            for (int k=0; k<5; k++) maxError = Math.max(maxError, Math.abs(expected[k]-actual[k]));
        }
        if (maxError > 2e-6) throw new AssertionError("Compiled logits differ " + maxError);
        return maxError;
    }

    static void select() throws Exception {
        InputStudy.field();
        for (String line : Files.readAllLines(InputStudy.PREV.resolve("clocks.tsv"))) {
            String[] v = line.split("\t");
            if (v[0].equals("dev")) PingLeague.dev.add(InputStudy.clock(Path.of(v[1])));
        }
        if (PingLeague.dev.size()!=8) throw new IllegalStateException("Wrong development split");
        var panel = InputStudy.panel(PingLeague.dev, false);
        double wtap = PingLeague.evaluate(PingLeague.WT,panel,null);
        var choices = new ArrayList<Arena.Fighter>(List.of(PingLeague.field.get(1)));
        for (String arm : List.of("base8","edge8","full8")) for (int seed : InputStudy.SEEDS) {
            String id = arm + "-" + seed;
            choices.add(InputStudy.fighter(id,InputStudy.DIR.resolve("fits/"+id+".net")));
        }
        choices.sort(Comparator.comparing(Arena.Fighter::name));
        var members = new ArrayList<Member>();
        for (var actor : choices)
            members.add(new Member(actor,canonical(actor.model()),PingLeague.evaluate(actor,panel,null)));
        double high = -1, maxError = 0;
        List<Member> selected = List.of();
        try (var out = new PrintWriter(Files.newBufferedWriter(DIR.resolve("development.tmp")))) {
            out.println("first,second,third,fourth,score,wtap");
            for (int a=0; a<7; a++) for (int b=a+1; b<8; b++) for (int c=b+1; c<9; c++) for (int d=c+1; d<10; d++) {
                var combination = List.of(members.get(a),members.get(b),members.get(c),members.get(d));
                var compiled = pooled(combination);
                maxError = Math.max(maxError,verify(combination,compiled));
                double score = PingLeague.evaluate(new Arena.Fighter("pooled",compiled),panel,null);
                String ids = String.join(",",combination.stream().map(m->m.actor().name()).toList());
                out.printf(Locale.ROOT,"%s,%.9f,%.9f%n",ids,score,wtap);
                if (score > high) { high=score; selected=combination; }
            }
            if (out.checkError()) throw new IOException("Failed to save development scores");
        }
        var bestMember = selected.stream().min(Comparator.<Member>comparingDouble(Member::merit).reversed()
                                               .thenComparing(m->m.actor().name())).orElseThrow();
        Files.writeString(DIR.resolve("pooled.net"), pooled(selected).encode()+"\n");
        Files.writeString(DIR.resolve("average.net"), averaged(selected).encode()+"\n");
        Files.writeString(DIR.resolve("member.net"), bestMember.actor().model().encode()+"\n");
        Files.writeString(DIR.resolve("members.txt"), String.join("\n", selected.stream().map(m->m.actor().name()).toList())+"\n");
        Files.writeString(DIR.resolve("best-member.txt"), bestMember.actor().name()+"\n");
        Files.move(DIR.resolve("development.tmp"),DIR.resolve("development.csv"),StandardCopyOption.ATOMIC_MOVE);
        System.out.println("Selected "+selected.stream().map(m->m.actor().name()).toList()+", score "+high+", max logit error "+maxError);
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(DIR);
        try { select(); } finally { PingLeague.pool.shutdown(); }
    }
}
