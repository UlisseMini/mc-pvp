import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Test the model's ray against the recorded position and aim at native click consumption. */
final class BoxingRayParity {
    public static void main(String[] args) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in));
        for (String line; (line = reader.readLine()) != null;) {
            String[] f = line.split(" ");
            var body = new PeriodicComboFightModel.Body(Double.parseDouble(f[0]));
            body.q = PeriodicComboFightModel.state(body.q.x(), Double.parseDouble(f[1]),
                    Double.parseDouble(f[2]), 0, 0, 0, true);
            var view = new PeriodicComboFightModel.View(Double.parseDouble(f[3]));
            view.y = Double.parseDouble(f[4]);
            view.z = Double.parseDouble(f[5]);
            body.pitch = Float.parseFloat(f[6]);
            body.headYaw = Float.parseFloat(f[7]);
            double headRay = PeriodicComboFightModel.ray(body, view);
            body.headYaw = Float.parseFloat(f[8]);
            double bodyRay = PeriodicComboFightModel.ray(body, view);
            System.out.println(headRay + " " + bodyRay);
        }
    }
}
