package lab;

/** One ordinary attack-key pause per locally observed unanswered streak. */
public final class ClickPause {
    private long targetCue, incomingCue, cueHead, lastHeadTime, resumeTick;
    private int streak;
    private boolean considered, haveHistory;
    private double previousGap, previousTargetY;

    public ClickPause() {}
    public ClickPause(ClickPause src) {
        targetCue=src.targetCue; incomingCue=src.incomingCue; cueHead=src.cueHead;
        lastHeadTime=src.lastHeadTime; resumeTick=src.resumeTick; streak=src.streak;
        considered=src.considered; haveHistory=src.haveHistory;
        previousGap=src.previousGap; previousTargetY=src.previousTargetY;
    }

    /** Called once per movement head. All distances and hurt cues are from this client. */
    public double[] observe(long now,long elapsed,long landed,long hurt,double gap,double range,
                            double targetY,double ownY,double radial,boolean sprint,double ping) {
        long dt=now-lastHeadTime;
        boolean historyReady=haveHistory && dt>0 && dt<=150;
        double gapChange=historyReady?(gap-previousGap)*50/dt:0;
        double targetRise=historyReady?(targetY-previousTargetY)*50/dt:0;
        lastHeadTime=now; previousGap=gap; previousTargetY=targetY; haveHistory=true;
        boolean incoming=hurt>incomingCue, outgoing=landed>targetCue;
        incomingCue=hurt; targetCue=landed;
        if(incoming || elapsed<2000){streak=0; considered=false;}
        if(outgoing){
            cueHead=now;
            if(elapsed>=2000 && !incoming)streak++;
        }
        long age=now-cueHead;
        if(considered || streak<3 || !historyReady || age<80 || age>210 || !sprint || radial<=.005 || range>3)
            return null;
        considered=true;
        return new double[]{range,gapChange,radial,targetY-ownY,targetRise,age,ping};
    }

    public void pause(long tick){resumeTick=tick+4;}
    public boolean allows(long tick){return tick>=resumeTick;}

    /** Indexed binary tree. Each interior node holds a feature, threshold and two forward children. */
    public static final class Tree {
        private final String[] nodes;
        private final int[] feature, left, right;
        private final double[] threshold;
        public Tree(String encoded){
            nodes=encoded.split("\\|",-1);
            feature=new int[nodes.length]; left=new int[nodes.length]; right=new int[nodes.length]; threshold=new double[nodes.length];
            for(int i=0;i<nodes.length;i++){
                if(nodes[i].equals("keep") || nodes[i].equals("pause")){feature[i]=-1;continue;}
                String[] fields=nodes[i].split(",",-1);
                if(fields.length!=4)throw new IllegalArgumentException("Invalid click-pause tree node");
                feature[i]=Integer.parseInt(fields[0]); threshold[i]=Double.parseDouble(fields[1]);
                left[i]=Integer.parseInt(fields[2]); right[i]=Integer.parseInt(fields[3]);
                if(feature[i]<0||feature[i]>=7||!Double.isFinite(threshold[i])||
                    left[i]<=i||right[i]<=i||left[i]>=nodes.length||right[i]>=nodes.length)
                    throw new IllegalArgumentException("Invalid click-pause tree bounds");
            }
        }
        public boolean pause(double[] x){
            int i=0;
            while(feature[i]>=0)i=x[feature[i]]<=threshold[i]?left[i]:right[i];
            return nodes[i].equals("pause");
        }
    }
}
