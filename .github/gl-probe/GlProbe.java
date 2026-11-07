import com.jogamp.opengl.*;
import java.nio.file.Path;

public class GlProbe {
  static void setEnv(String name, String value) throws Throwable {
    var linker = java.lang.foreign.Linker.nativeLinker();
    var k32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global());
    var h = linker.downcallHandle(k32.find("SetEnvironmentVariableW").orElseThrow(),
        java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
            java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS));
    try (var a = java.lang.foreign.Arena.ofConfined()) {
      int ok = (int) h.invokeExact(a.allocateFrom(name, java.nio.charset.StandardCharsets.UTF_16LE),
          a.allocateFrom(value, java.nio.charset.StandardCharsets.UTF_16LE));
    }
  }

  public static void main(String[] args) throws Throwable {
    if (args.length > 0) {
      Path dir = Path.of(args[0]);
      setEnv("GALLIUM_DRIVER", "llvmpipe");
      System.load(dir.resolve("libgallium_wgl.dll").toAbsolutePath().toString());
      System.load(dir.resolve("opengl32.dll").toAbsolutePath().toString());
      System.setProperty("jogamp.primary.library.path", dir.toAbsolutePath().toString());
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
