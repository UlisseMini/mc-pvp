package practice.bridge;

import practice.api.WorldFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import java.util.*;

final class WorldDrawing {
    private static int sphereMesh;
    // Opacity increases slightly inside the true boundary; no glow extends beyond actual reach.
    private static final double[] FIELD_RADII={1,.99,.975,.95};
    private static final float[] FIELD_OPACITY={.12f,.18f,.28f,.42f};
    static void draw(WorldFrame frame,float partial){draw(frame,partial,1);}
    static void draw(WorldFrame frame,float partial,float lineWidth){
        if(frame==null)return;Entity camera=Minecraft.func_71410_x().func_175606_aa();if(camera==null)return;
        double cx=camera.field_70142_S+(camera.field_70165_t-camera.field_70142_S)*partial;
        double cy=camera.field_70137_T+(camera.field_70163_u-camera.field_70137_T)*partial;
        double cz=camera.field_70136_U+(camera.field_70161_v-camera.field_70136_U)*partial;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_CURRENT_BIT|GL11.GL_LINE_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_COLOR_BUFFER_BIT|GL11.GL_POLYGON_BIT);
        GL11.glPushMatrix();
        try{
            GL11.glTranslated(-cx,-cy,-cz);
            GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glDepthMask(false);GL11.glLineWidth(lineWidth);
            fields(frame.spheres,cx,cy,cz);
            GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL11.GL_DEPTH_TEST);
            int n=0;GL11.glBegin(GL11.GL_LINES);
            for(WorldFrame.Line l:frame.lines){if(++n>1024)break;if(!finite(l.x,l.y,l.z,l.xx,l.yy,l.zz))continue;color(l.color);GL11.glVertex3d(l.x,l.y,l.z);GL11.glVertex3d(l.xx,l.yy,l.zz);}
            GL11.glEnd();n=0;
            for(WorldFrame.Box b:frame.boxes){if(++n>64)break;if(!finite(b.x,b.y,b.z,b.width,b.height,b.depth,b.yaw)||b.width<=0||b.height<=0||b.depth<=0||Math.max(b.width,Math.max(b.height,b.depth))>8)continue;
                GL11.glPushMatrix();GL11.glTranslated(b.x,b.y,b.z);GL11.glRotated(b.yaw,0,1,0);color(b.color);box(b.width/2,b.height/2,b.depth/2);GL11.glPopMatrix();}
        }finally{GL11.glPopMatrix();GL11.glPopAttrib();}
    }
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static void color(int c){GL11.glColor4f(((c>>>16)&255)/255f,((c>>>8)&255)/255f,(c&255)/255f,(c>>>24)/255f);}
    private static void fields(List<WorldFrame.Sphere> source,final double x,final double y,final double z){
        List<WorldFrame.Sphere> fields=new ArrayList<>();
        for(WorldFrame.Sphere s:source){if(fields.size()==16)break;if(finite(s.x,s.y,s.z,s.radius)&&s.radius>0&&s.radius<=16&&(s.color>>>24)!=0)fields.add(s);}
        if(fields.isEmpty())return;
        Collections.sort(fields,new Comparator<WorldFrame.Sphere>(){public int compare(WorldFrame.Sphere a,WorldFrame.Sphere b){return Double.compare(distance(b,x,y,z),distance(a,x,y,z));}});
        // The world depth buffer clips the translucent field where it meets the ground.
        // Two culled passes work from inside or outside the field, including third-person cameras.
        GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_CULL_FACE);
        if(sphereMesh==0){int id=GL11.glGenLists(1);if(id!=0){GL11.glNewList(id,GL11.GL_COMPILE);unitSphere();GL11.glEndList();sphereMesh=id;}}
        for(WorldFrame.Sphere s:fields){
            GL11.glPushMatrix();GL11.glTranslated(s.x,s.y,s.z);
            for(int pass=0;pass<2;pass++){
                GL11.glCullFace(pass==0?GL11.GL_FRONT:GL11.GL_BACK);
                for(int n=0;n<FIELD_RADII.length;n++){
                    int i=pass==0?n:FIELD_RADII.length-1-n;double r=s.radius*FIELD_RADII[i];
                    GL11.glColor4f(((s.color>>>16)&255)/255f,((s.color>>>8)&255)/255f,(s.color&255)/255f,(s.color>>>24)/255f*FIELD_OPACITY[i]);
                    GL11.glPushMatrix();GL11.glScaled(r,r,r);if(sphereMesh!=0)GL11.glCallList(sphereMesh);else unitSphere();GL11.glPopMatrix();
                }
            }
            GL11.glPopMatrix();
        }
    }
    private static double distance(WorldFrame.Sphere s,double x,double y,double z){double dx=s.x-x,dy=s.y-y,dz=s.z-z;return dx*dx+dy*dy+dz*dz;}
    private static void unitSphere(){
        final int rings=32,segments=64;
        for(int ring=0;ring<rings;ring++){
            double low=-Math.PI/2+Math.PI*ring/rings,high=-Math.PI/2+Math.PI*(ring+1)/rings;
            double loY=Math.sin(low),loR=Math.cos(low),hiY=Math.sin(high),hiR=Math.cos(high);
            GL11.glBegin(GL11.GL_QUAD_STRIP);
            for(int segment=0;segment<=segments;segment++){double angle=2*Math.PI*segment/segments,c=Math.cos(angle),s=Math.sin(angle);GL11.glVertex3d(loR*c,loY,loR*s);GL11.glVertex3d(hiR*c,hiY,hiR*s);}
            GL11.glEnd();
        }
    }
    private static void box(double x,double y,double z){
        GL11.glBegin(GL11.GL_QUADS);
        face(-x,-y,-z,x,-y,-z,x,y,-z,-x,y,-z);face(-x,-y,z,-x,y,z,x,y,z,x,-y,z);
        face(-x,-y,-z,-x,y,-z,-x,y,z,-x,-y,z);face(x,-y,-z,x,-y,z,x,y,z,x,y,-z);
        face(-x,-y,-z,-x,-y,z,x,-y,z,x,-y,-z);face(-x,y,-z,x,y,-z,x,y,z,-x,y,z);GL11.glEnd();
    }
    private static void face(double... a){for(int i=0;i<12;i+=3)GL11.glVertex3d(a[i],a[i+1],a[i+2]);}
}
