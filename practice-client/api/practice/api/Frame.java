package practice.api;

import java.util.*;

public final class Frame {
    public final boolean holdSprint;
    public final List<Line> lines;
    public final int edgeColor,edgeWidth;
    public final List<Rectangle> rectangles;
    public Frame(boolean holdSprint,List<Line> lines,int edgeColor,int edgeWidth) {
        this(holdSprint,lines,edgeColor,edgeWidth,Collections.emptyList());
    }
    public Frame(boolean holdSprint,List<Line> lines,int edgeColor,int edgeWidth,List<Rectangle> rectangles) {
        this.holdSprint=holdSprint;this.lines=Collections.unmodifiableList(new ArrayList<>(lines));
        this.edgeColor=edgeColor;this.edgeWidth=edgeWidth;
        this.rectangles=Collections.unmodifiableList(new ArrayList<>(rectangles));
    }
    /** GUI-scaled pixels; enough for timing bars, plots and history indicators. */
    public static final class Rectangle {
        public final int left,top,right,bottom,color;
        public Rectangle(int left,int top,int right,int bottom,int color){this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.color=color;}
    }
    public static Frame empty(){return new Frame(false,Collections.emptyList(),0,0);}
    public static final class Line {
        public final String text;
        /** When right aligned, x is the inset from the right edge. */
        public final int x,y,color; public final boolean right;
        public final float scale;public final boolean centered;
        public Line(String text,int x,int y,int color,boolean right){
            this(text,x,y,color,right,1,false);
        }
        public Line(String text,int x,int y,int color,boolean right,float scale,boolean centered){this.text=text;this.x=x;this.y=y;this.color=color;this.right=right;this.scale=scale;this.centered=centered;}
    }
}
