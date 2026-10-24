/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer3d.vr;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.util.StringUtil;

/**
 * Addis Ortho: software 3D for Windows PCs whose graphics chip has no OpenGL 3.3 (for example Intel
 * HD Graphics 2000/3000, whose last Windows driver stops at OpenGL 3.1).
 *
 * <p>The Windows installer carries the Mesa llvmpipe renderer (opengl32.dll and libgallium_wgl.dll)
 * in {@code app/mesa}. When the user chooses software 3D, both files are loaded before JOGL starts.
 * Windows then hands the already loaded Mesa opengl32.dll to every later request for "opengl32.dll"
 * by name, so JOGL draws with the processor instead of the graphics driver. PCs that do not choose
 * it keep the system OpenGL.
 */
public final class SoftwareGl {
  private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareGl.class);

  /** Local persistence key: the user chose software 3D. */
  public static final String P_SOFTWARE_GL = "opengl.software"; // NON-NLS

  /** JVM property that forces software 3D, for testing: {@code -Dweasis.3d.software=true}. */
  public static final String P_FORCE_SOFTWARE = "weasis.3d.software"; // NON-NLS

  /** JVM property giving the folder of the Mesa DLLs, for testing outside an installed app. */
  public static final String P_MESA_PATH = "weasis.3d.mesa.path"; // NON-NLS

  private static final String GALLIUM_DLL = "libgallium_wgl.dll"; // NON-NLS
  private static final String OPENGL_DLL = "opengl32.dll"; // NON-NLS
  private static final String PRIMARY_LIB_PATH = "jogamp.primary.library.path"; // NON-NLS
  private static final String WGL_GDI_FUNCS =
      "jogl.windows.useWGLVersionOf5WGLGDIFuncSet"; // NON-NLS

  private static volatile boolean active;

  private SoftwareGl() {}

  /** Whether this is a Windows install that carries the Mesa renderer. */
  public static boolean isAvailable() {
    Path dir = mesaDir();
    return dir != null
        && Files.isRegularFile(dir.resolve(GALLIUM_DLL))
        && Files.isRegularFile(dir.resolve(OPENGL_DLL));
  }

  /** Whether the user chose software 3D (it takes effect at the next start). */
  public static boolean isChosen() {
    if (Boolean.getBoolean(P_FORCE_SOFTWARE)) {
      return true;
    }
    return GuiUtils.getUICore().getLocalPersistence().getBooleanProperty(P_SOFTWARE_GL, false);
  }

  public static void setChosen(boolean chosen) {
    GuiUtils.getUICore().getLocalPersistence().putBooleanProperty(P_SOFTWARE_GL, chosen);
  }

  /** Whether the Mesa renderer was loaded in this session. */
  public static boolean isActive() {
    return active;
  }

  /**
   * Loads the Mesa renderer when the user chose it. Must run before JOGL first loads OpenGL, so
   * {@link OpenglUtils} calls it ahead of {@code GLProfile.initSingleton()}.
   */
  static synchronized void loadIfChosen() {
    if (active || !isChosen()) {
      return;
    }
    if (!isAvailable()) {
      LOGGER.warn("Software 3D was chosen but the Mesa renderer is not in this install");
      return;
    }
    Path dir = mesaDir();
    try {
      // Mesa would otherwise pick its D3D12 driver, which on these PCs runs on Microsoft's own
      // software renderer (WARP); llvmpipe is Mesa's faster processor renderer. Mesa reads it from
      // the process environment when its DLL starts, so it must be set before loading.
      setProcessEnv("GALLIUM_DRIVER", "llvmpipe"); // NON-NLS
      // opengl32.dll imports libgallium_wgl.dll by name; a DLL loaded by full path does not find
      // its neighbours, so load the dependency first.
      System.load(dir.resolve(GALLIUM_DLL).toString());
      System.load(dir.resolve(OPENGL_DLL).toString());
      // JOGL opens OpenGL by the full path C:\Windows\System32\opengl32.dll unless a primary
      // library folder is given, which it searches first.
      System.setProperty(PRIMARY_LIB_PATH, dir.toString());
      // JOGL otherwise chooses and sets the pixel format through GDI, which talks to the system
      // driver, so Mesa cannot make a context; this makes it use Mesa's wgl* versions instead.
      System.setProperty(WGL_GDI_FUNCS, "true");
      active = true;
      LOGGER.info("Software 3D: Mesa renderer loaded from {}", dir);
    } catch (Throwable t) {
      LOGGER.error("Software 3D: cannot load the Mesa renderer from {}", dir, t);
    }
  }

  /** Sets a variable in this process's Windows environment, which Java cannot do on its own. */
  private static void setProcessEnv(String name, String value) throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global()); // NON-NLS
    MethodHandle setEnv =
        linker.downcallHandle(
            kernel32.find("SetEnvironmentVariableW").orElseThrow(), // NON-NLS
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    try (Arena arena = Arena.ofConfined()) {
      int ok =
          (int)
              setEnv.invokeExact(
                  arena.allocateFrom(name, StandardCharsets.UTF_16LE),
                  arena.allocateFrom(value, StandardCharsets.UTF_16LE));
      if (ok == 0) {
        LOGGER.warn("Software 3D: cannot set {}", name);
      }
    }
  }

  private static Path mesaDir() {
    try {
      String custom = System.getProperty(P_MESA_PATH);
      if (StringUtil.hasText(custom)) {
        return Path.of(custom);
      }
      if (!System.getProperty("os.name", "").startsWith("Windows")) { // NON-NLS
        return null;
      }
      // jpackage sets the path of the launcher exe; the input folder sits in "app" beside it.
      String exe = System.getProperty("jpackage.app-path"); // NON-NLS
      if (!StringUtil.hasText(exe)) {
        return null;
      }
      Path parent = Path.of(exe).getParent();
      return parent == null ? null : parent.resolve("app").resolve("mesa"); // NON-NLS
    } catch (Exception e) {
      LOGGER.error("Software 3D: cannot locate the Mesa folder", e);
      return null;
    }
  }
}
