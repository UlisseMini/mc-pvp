package practice.api;

import java.util.*;

public final class Frame {
    public final boolean holdSprint;
    public final List<Line> lines;
    public final int edgeColor,edgeWidth;
    public Frame(boolean holdSprint,List<Line> lines,int edgeColor,int edgeWidth) {
        this.holdSprint=holdSprint;this.lines=Collections.unmodifiableList(new ArrayList<>(lines));
        this.edgeColor=edgeColor;this.edgeWidth=edgeWidth;
    }
    public static Frame empty(){return new Frame(false,Collections.emptyList(),0,0);}
    public static final class Line {
        public final String text;
        /** When right aligned, x is the inset from the right edge. */
        public final int x,y,color; public final boolean right;
        public Line(String text,int x,int y,int color,boolean right){
            this.text=text;this.x=x;this.y=y;this.color=color;this.right=right;
        }
    }
}
