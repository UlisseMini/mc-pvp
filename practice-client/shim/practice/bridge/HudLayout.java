package practice.bridge;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** HUD positions are stored as fractions of the space the widget can move in. */
final class HudLayout {
    private final Path path;
    private final Properties positions=new Properties();

    HudLayout(Path directory){
        path=directory.resolve("hud-layout.properties");
        if(Files.isRegularFile(path))try(InputStream in=Files.newInputStream(path)){positions.load(in);}
        catch(IOException|IllegalArgumentException failure){positions.clear();}
    }

    Point position(String id,int defaultX,int defaultY,int maxX,int maxY){
        return new Point(axis(id+".x",defaultX,maxX),axis(id+".y",defaultY,maxY));
    }

    private int axis(String key,int fallback,int max){
        max=Math.max(0,max);
        String value=positions.getProperty(key);
        if(value!=null)try{
            double fraction=Double.parseDouble(value);
            if(Double.isFinite(fraction))return (int)Math.round(Math.max(0,Math.min(1,fraction))*max);
        }catch(NumberFormatException ignored){}
        return Math.max(0,Math.min(max,fallback));
    }

    void move(String id,int x,int y,int maxX,int maxY){
        positions.setProperty(id+".x",fraction(x,maxX));
        positions.setProperty(id+".y",fraction(y,maxY));
    }

    private static String fraction(int position,int max){
        return Double.toString(max>0?Math.max(0,Math.min(max,position))/(double)max:0);
    }

    void reset(){positions.clear();}

    void save()throws IOException{
        Path temporary=Files.createTempFile(path.getParent(),"hud-layout-",".tmp");
        try{
            try(OutputStream out=Files.newOutputStream(temporary)){positions.store(out,"Practice HUD layout");}
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
}
