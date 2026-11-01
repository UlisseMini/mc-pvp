package lab;

/** Presentation labels only. Account names and UUIDs remain the match identities. */
final class MatchLabels {
    private MatchLabels() {}

    static String label(String name,String policy,String otherPolicy,String side) {
        if("human".equals(policy))return name;
        return policy+(policy.equals(otherPolicy)?"-"+side:"");
    }

    /** Vanilla replay profiles still need a legal, at-most-16-character fallback. */
    static String profileName(String label) {
        String safe=label.replaceAll("[^A-Za-z0-9_]","_");
        if(safe.length()<=16)return safe.isEmpty()?"Replay":safe;
        String suffix=label.endsWith("-a")?"_a":label.endsWith("-b")?"_b":"";
        String hash=String.format(java.util.Locale.ROOT,"%04x",label.hashCode()&0xffff);
        return safe.substring(0,11-suffix.length())+"_"+hash+suffix;
    }
}
