package lab;

/** The recorded MMC fighting surface, rotated so the gold spawn blocks lie on the lab's X axis. */
public final class SumoFootprint {
    private SumoFootprint(){}
    public static final int DEFAULT=13, LEGACY=10;
    public static final String SHAPE="mmc_recorded_13_v1", CLIP="fb302faee0f03c27bcaac437a64752fa";
    // Z rows from north to south; stone, stone brick, mossy stone brick, gold or air.
    private static final String[] ROWS={
        "....bssms....",
        "..smbsbbssb..",
        ".ssbmsbbsbmm.",
        ".msssssmbssm.",
        "msbsmmsbsbsbs",
        "sbmsbsbsmmsss",
        "bmgmmmbbbsgsb",
        "ssmmsssssssbs",
        "sbmmssmbssbmb",
        ".ssmssbsssss.",
        ".bsbsssmssms.",
        "..sbmmsssss..",
        "....bssss...."
    };
    public static double centerOffset(int diameter){return diameter==DEFAULT?.5:0.;}
    public static double startGap(int diameter){return diameter==DEFAULT?8.:6.;}
    public static int state(int x,int z,int diameter){
        if(diameter!=DEFAULT){
            double dx=x+.5,dz=z+.5;
            return dx*dx+dz*dz<=diameter*diameter*.25?7<<4:0;
        }
        int row=z+6,col=x+6;
        if(row<0||row>=ROWS.length||col<0||col>=ROWS[row].length())return 0;
        switch(ROWS[row].charAt(col)){
            case 's':return 1<<4;
            case 'b':return 98<<4;
            case 'm':return (98<<4)|1;
            case 'g':return 41<<4;
            default:return 0;
        }
    }
    public static boolean cell(int x,int z,int diameter){return state(x,z,diameter)!=0;}
}
