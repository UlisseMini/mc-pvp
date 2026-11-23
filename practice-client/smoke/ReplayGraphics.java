package practice.smoke;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/** Graphics setup shared by the offline screenshot and showcase clients. */
final class ReplayGraphics {
    private ReplayGraphics() {}

    static void restoreContext() throws LWJGLException {
        if (!Display.isCurrent()) Display.makeCurrent();
    }

    static void prepare(Minecraft minecraft) throws LWJGLException {
        restoreContext();
        // Forge's startup context handoff can leave the block atlas name pointing
        // at a menu texture on macOS. Rebuild resources once in the capture context.
        minecraft.func_110436_a();
        verifyAtlas(minecraft.func_147117_R());
    }

    private static void verifyAtlas(TextureMap atlas) {
        TextureAtlasSprite stone = atlas.func_110572_b("minecraft:blocks/stone");
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int width, height;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas.func_110552_b());
        try {
            width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        // The sprite's UV span converts back to its pixel size on the live atlas.
        // Half a pixel allows for Minecraft's small UV inset.
        if (Math.abs(width * (stone.func_94212_f() - stone.func_94209_e()) - stone.func_94211_a()) > .5
                || Math.abs(height * (stone.func_94210_h() - stone.func_94206_g()) - stone.func_94216_b()) > .5) {
            throw new IllegalStateException("OpenGL block atlas does not match the loaded sprites");
        }
    }
}
