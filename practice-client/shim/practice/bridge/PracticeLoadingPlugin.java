package practice.bridge;
import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.TransformerExclusions({"practice.bridge.PracticeTransformer","practice.bridge.PracticeLoadingPlugin"})
public final class PracticeLoadingPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass(){return new String[]{"practice.bridge.PracticeTransformer"};}
    public String getModContainerClass(){return null;}
    public String getSetupClass(){return null;}
    public void injectData(Map<String,Object> data){}
    public String getAccessTransformerClass(){return null;}
}
