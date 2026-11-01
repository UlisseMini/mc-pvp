package practice.bridge;

import practice.api.WorldFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

final class WorldDrawing {
    static void draw(WorldFrame frame,float partial){
        if(frame==null)return;Entity camera=Minecraft.func_71410_x().func_175606_aa();if(camera==null)return;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT|GL11.GL_CURRENT_BIT|GL11.GL_LINE_BIT|GL11.GL_DEPTH_BUFFER_BIT|GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPushMatrix();
        try{
            GL11.glTranslated(-(camera.field_70142_S+(camera.field_70165_t-camera.field_70142_S)*partial),-(camera.field_70137_T+(camera.field_70163_u-camera.field_70137_T)*partial),-(camera.field_70136_U+(camera.field_70161_v-camera.field_70136_U)*partial));
            GL11.glDisable(GL11.GL_TEXTURE_2D);GL11.glDisable(GL11.GL_LIGHTING);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);GL11.glDepthMask(false);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glLineWidth(1);
            int n=0;GL11.glBegin(GL11.GL_LINES);
            for(WorldFrame.Line l:frame.lines){if(++n>1024)break;if(!finite(l.x,l.y,l.z,l.xx,l.yy,l.zz))continue;color(l.color);GL11.glVertex3d(l.x,l.y,l.z);GL11.glVertex3d(l.xx,l.yy,l.zz);}
            GL11.glEnd();n=0;
            for(WorldFrame.Box b:frame.boxes){if(++n>64)break;if(!finite(b.x,b.y,b.z,b.width,b.height,b.depth,b.yaw)||b.width<=0||b.height<=0||b.depth<=0||Math.max(b.width,Math.max(b.height,b.depth))>8)continue;
                GL11.glPushMatrix();GL11.glTranslated(b.x,b.y,b.z);GL11.glRotated(b.yaw,0,1,0);color(b.color);box(b.width/2,b.height/2,b.depth/2);GL11.glPopMatrix();}
        }finally{GL11.glPopMatrix();GL11.glPopAttrib();}
    }
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static void color(int c){GL11.glColor4f(((c>>>16)&255)/255f,((c>>>8)&255)/255f,(c&255)/255f,(c>>>24)/255f);}
    private static void box(double x,double y,double z){
        GL11.glBegin(GL11.GL_QUADS);
        face(-x,-y,-z,x,-y,-z,x,y,-z,-x,y,-z);face(-x,-y,z,-x,y,z,x,y,z,x,-y,z);
        face(-x,-y,-z,-x,y,-z,-x,y,z,-x,-y,z);face(x,-y,-z,x,-y,z,x,y,z,x,y,-z);
        face(-x,-y,-z,-x,-y,z,x,-y,z,x,-y,-z);face(-x,y,-z,x,y,-z,x,y,z,-x,y,z);GL11.glEnd();
    }
    private static void face(double... a){for(int i=0;i<12;i+=3)GL11.glVertex3d(a[i],a[i+1],a[i+2]);}
}
