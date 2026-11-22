import java.io.*;
import java.nio.file.*;
import lab.DuelNetwork;

/** Rebuild the teacher's actual inputs from one client's native decision log. */
final class ReplayInputs {
    public static void main(String[] args) throws IOException {
        String weights = Files.readString(Path.of(args[0])).trim();
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[1]))));
             var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[2]))))) {
            int games = in.readInt();
            for (int g = 0; g < games; g++) {
                var net = DuelNetwork.decode(weights);
                int heads = in.readInt();
                for (int i = 0; i < heads; i++) {
                    double[] v = new double[14];
                    for (int k = 0; k < v.length; k++) v[k] = in.readDouble();
                    int applied = in.readUnsignedByte();
                    int proposed = net.step(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],
                        v[9]!=0,v[10]!=0,age(v[11]),age(v[12]),v[13]);
                    net.applied(applied);
                    out.writeFloat(proposed);
                    for (double x : net.features()) out.writeFloat((float)x);
                    for (double score : net.scores()) out.writeFloat((float)score);
                }
            }
            if (in.read() != -1) throw new IOException("Trailing input");
        }
    }

    private static double age(double v) { return v == -1 ? Double.POSITIVE_INFINITY : v; }
}
