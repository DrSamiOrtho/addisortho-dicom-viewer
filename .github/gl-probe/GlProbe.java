import com.jogamp.opengl.*;
import java.nio.file.Path;

public class GlProbe {
  public static void main(String[] args) {
    if (args.length > 0) {
      Path dir = Path.of(args[0]);
      System.load(dir.resolve("libgallium_wgl.dll").toAbsolutePath().toString());
      System.load(dir.resolve("opengl32.dll").toAbsolutePath().toString());
      System.setProperty("jogl.windows.useWGLVersionOf5WGLGDIFuncSet", "true");
    }
    GLProfile.initSingleton();
    GLProfile profile;
    try {
      profile = GLProfile.get(GLProfile.GL4);
    } catch (Exception e) {
      profile = GLProfile.get(GLProfile.GL3);
    }
    GLDrawable drawable = GLDrawableFactory.getFactory(profile)
        .createOffscreenDrawable(null, new GLCapabilities(profile), null, 1, 1);
    drawable.setRealized(true);
    GLContext ctx = drawable.createContext(null);
    ctx.makeCurrent();
    GL gl = ctx.getGL();
    int[] tex = new int[1];
    gl.glGetIntegerv(GL2ES2.GL_MAX_3D_TEXTURE_SIZE, tex, 0);
    System.out.println("PROBE profile=" + profile.getName()
        + " version=" + gl.glGetString(GL.GL_VERSION)
        + " renderer=" + gl.glGetString(GL.GL_RENDERER)
        + " max3d=" + tex[0]);
    if (gl.isGL4()) {
      GL4 gl4 = gl.getGL4();
      int sh = gl4.glCreateShader(GL4.GL_COMPUTE_SHADER);
      gl4.glShaderSource(sh, 1, new String[] {
          "#version 430\nlayout(local_size_x=1) in;\nvoid main(){}\n"}, null);
      gl4.glCompileShader(sh);
      int[] ok = new int[1];
      gl4.glGetShaderiv(sh, GL4.GL_COMPILE_STATUS, ok, 0);
      System.out.println("PROBE compute shader compiled=" + (ok[0] != 0));
    }
    ctx.release();
    System.exit(0);
  }
}
