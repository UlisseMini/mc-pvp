package practice.bridge;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ImageBufferDownload;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import static practice.replay.PoseTrack.object;

/** Resolve recorded skin identities through Minecraft's local PNG cache. */
final class ReplaySkins {
    private static final Pattern HASH=Pattern.compile("[a-f0-9]{32,64}");
    private static final Pattern SKIN_URL=Pattern.compile("https?://textures\\.minecraft\\.net/texture/([a-f0-9]{32,64})");
    private static final String TEXTURE_FOLDER="skins/";
    private final Gson gson;
    private final Path directory;
    private final Map<UUID,Appearance> recorded=new HashMap<>(),fallback=new HashMap<>();
    private final Map<String,ResourceLocation> loaded=new HashMap<>();
    private Path cache;

    private static final class Appearance {
        final String hash,model;

        Appearance(String hash,Object model){
            this.hash=hash;
            this.model="slim".equals(model)?"slim":"default";
        }
    }

    ReplaySkins(Path directory,Gson gson){this.directory=directory;this.gson=gson;}

    void begin(Minecraft mc){
        Object assetFolder=GameObservations.field(mc,"fileAssets");
        cache=(assetFolder instanceof File?(File)assetFolder:new File(mc.field_71412_D,"assets")).toPath().resolve("skins");
        fallback.clear();
        Properties props=new Properties();
        try(InputStream in=Files.newInputStream(directory.resolve("replay-skins.properties"))){props.load(in);}
        catch(IOException|IllegalArgumentException unavailable){return;}
        for(String key:props.stringPropertyNames()){
            if(!key.endsWith(".texture"))continue;
            UUID id=uuid(key.substring(0,key.length()-8));
            String hash=props.getProperty(key);
            if(id!=null&&validHash(hash))fallback.put(id,new Appearance(hash,props.getProperty(id+".model")));
        }
    }

    void resetWorld(){recorded.clear();}

    void close(Minecraft mc){
        for(ResourceLocation resource:loaded.values())if(resource!=null)mc.func_110434_K().func_147645_c(resource);
        loaded.clear();recorded.clear();fallback.clear();
    }

    void remember(GameProfile profile){
        UUID id=profile.getId();
        if(id==null)return;
        for(Property property:profile.getProperties().get("textures")){
            try{
                String value=property.getValue();
                if(value.length()>16384)continue;
                String decoded=new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);
                Map<String,Object> skin=object(object(object(gson.fromJson(decoded,Map.class)).get("textures")).get("SKIN"));
                Matcher match=SKIN_URL.matcher(String.valueOf(skin.get("url")));
                if(match.matches()){
                    recorded.put(id,new Appearance(match.group(1),object(skin.get("metadata")).get("model")));
                    return;
                }
            }catch(RuntimeException malformed){/* Skip this texture property. */}
        }
    }

    void remember(Map<String,Object> pose){
        Object hash=pose.get("skin_texture");
        if(!(hash instanceof String)||!validHash((String)hash))return;
        UUID id=uuid(pose.get("uuid"));
        if(id!=null)recorded.put(id,new Appearance((String)hash,pose.get("skin_model")));
    }

    ResourceLocation texture(UUID id){
        Appearance skin=recorded.getOrDefault(id,fallback.get(id));
        ResourceLocation local=skin==null?null:cached(skin.hash);
        return local==null?DefaultPlayerSkin.func_177334_a(id):local;
    }

    String model(UUID id){
        Appearance skin=recorded.getOrDefault(id,fallback.get(id));
        return skin==null||cached(skin.hash)==null?DefaultPlayerSkin.func_177332_b(id):skin.model;
    }

    private ResourceLocation cached(String hash){
        if(loaded.containsKey(hash))return loaded.get(hash);
        Path png=cache.resolve(hash.substring(0,2)).resolve(hash);
        ResourceLocation location=null;
        try{
            if(Files.isRegularFile(png,LinkOption.NOFOLLOW_LINKS)&&Files.size(png)<=131072){
                BufferedImage skin=readSkin(png);
                if(skin!=null){
                    ResourceLocation key=new ResourceLocation("pvp-practice","replay-skin/"+hash);
                    BufferedImage normalized=new ImageBufferDownload().func_78432_a(skin);
                    if(Minecraft.func_71410_x().func_110434_K().func_110579_a(key,new DynamicTexture(normalized)))location=key;
                }
            }
        }catch(IOException|RuntimeException unavailable){/* Use the bundled default for unusable files. */}
        loaded.put(hash,location);
        return location;
    }

    private static BufferedImage readSkin(Path path)throws IOException{
        ImageReader png=ImageIO.getImageReadersByFormatName("png").next();
        try(ImageInputStream input=ImageIO.createImageInputStream(path.toFile())){
            if(input==null)return null;
            png.setInput(input,true,true);
            int height=png.getHeight(0);
            return png.getWidth(0)==64&&(height==32||height==64)?png.read(0):null;
        }finally{png.dispose();}
    }

    static void captureAppearance(Map<String,Object> data,net.minecraft.client.entity.AbstractClientPlayer player){
        // Inspect only textures Minecraft has already resolved during normal play.
        if(!player.func_152123_o())return;
        String path=player.func_110306_p().func_110623_a();
        if(!path.startsWith(TEXTURE_FOLDER))return;
        String hash=path.substring(TEXTURE_FOLDER.length());
        if(validHash(hash)){
            data.put("skin_texture",hash);
            data.put("skin_model",player.func_175154_l());
        }
    }

    static UUID uuid(Object raw){
        if(!(raw instanceof String))return null;
        try{return UUID.fromString((String)raw);}
        catch(IllegalArgumentException invalid){return null;}
    }

    private static boolean validHash(String hash){return hash!=null&&HASH.matcher(hash).matches();}
}
