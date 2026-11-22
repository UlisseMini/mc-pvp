package practice.tests;

import practice.core.forecast.BedWarsForecastHistory;
import practice.core.forecast.BedWarsForecastHistory.Input;
import practice.core.forecast.BedWarsForecastHistory.Observation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BedWarsForecastHistoryCheck {
    private static final UUID LOCAL = UUID.fromString("344df49f-efc4-4b1b-9eb5-cbc7d6b07842");
    private static final UUID OTHER = UUID.fromString("e9513870-42b8-4d5a-bd58-8694f6beef46");
    private static int checks;

    private static void check(boolean pass, String description) {
        checks++;
        if (!pass) throw new AssertionError(description);
    }

    private static void close(float actual, double expect, String description) {
        check(Math.abs(actual - expect) < 2e-5, description + " expected " + expect + " got " + actual);
    }

    private static Map<UUID, Observation> pair(int second, boolean other) {
        Map<UUID, Observation> observations = new LinkedHashMap<>();
        observations.put(LOCAL, new Observation(second / 2f, 75, 0, 0, false, false));
        if (other) observations.put(OTHER, new Observation(second / 2f + 15, 78, 3, (float)(Math.PI/2), true, true));
        return observations;
    }

    public static void main(String[] args) {
        BedWarsForecastHistory history = new BedWarsForecastHistory();
        for (int second = 2; second <= 190; second++) {
            Map<UUID, Input> frame = history.add(second, LOCAL, pair(second, second != 20));
            if (second == 4) {
                float[] features = frame.get(OTHER).features;
                close(features[0], 0, "heading-local right");
                close(features[2], -.5, "heading-local forward");
                close(features[6], 1, "2s previous seen");
                close(features[7], 0, "8s previous missing");
                close(features[8], 1, "sneak");
                close(features[10], .3, "opening altitude");
                close(features[11], -17/55.0, "heading-local world forward");
                close(features[12], Math.log1p(4/30.0), "elapsed feature");
                close(features[13], 2/6.0, "observed roster" );
                close(features[14], Math.sqrt(243)/50, "distance from local player");
                close(features[15], 0, "other player");
                close(features[16], 1, "sword feature");
                close(frame.get(OTHER).velocity[0], .5, "world velocity");
                close(frame.get(LOCAL).features[15], 1, "local player");
            }
            if (second == 29) check(history.examples(LOCAL,5,second).length == 0, "first bucket holds until 30s");
            if (second == 30) {
                float[][] rows = history.examples(LOCAL,5,second);
                check(rows.length == 8, "5s labels completed at 8,11,...,29");
                close(rows[0][0], 1.5, "first 5s issue x");
                close(rows[0][23], 4, "first 5s label x");
                close(rows[7][23], 14.5, "latest completed label x");
                check(history.examples(OTHER,5,second).length == 7, "absent future player contributes no label");
                close(history.examples(OTHER,5,second)[0][26], Math.PI/2, "support yaw");
                check(history.examples(LOCAL,10,second).length == 6, "10s task uses its own labels");
            }
            if (second == 32) {
                float[][] rows = history.examples(LOCAL,5,second);
                check(rows.length == 8, "completed labels after the boundary are withheld");
                close(rows[7][23], 14.5, "same label snapshot across a 30-second epoch");
            }
            if (second == 160) {
                float[][] rows = history.examples(LOCAL,5,second);
                check(rows.length == 32, "bounded newest labeled history");
                close(rows[0][23], 28, "oldest retained completed label");
                close(rows[31][23], 74.5, "latest permitted completed label");
                check(history.examples(LOCAL,120,second).length == 10, "120s labels use the correct lookback");
            }
        }
        history.clear();
        check(history.examples(LOCAL,5,120).length == 0, "match reset clears supports");
        close(history.add(2,LOCAL,pair(2,true)).get(OTHER).features[6], 0, "new match drops prior frames");
        check(!BedWarsForecastHistory.withinGrid(new float[]{128,72,0}), "exclusive lattice upper bound");
        System.out.println("BedWarsForecastHistoryCheck passed: " + checks + " checks");
    }
}
