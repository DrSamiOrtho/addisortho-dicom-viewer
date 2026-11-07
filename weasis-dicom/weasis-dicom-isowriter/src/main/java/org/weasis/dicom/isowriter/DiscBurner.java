/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.isowriter;

import com.formdev.flatlaf.util.SystemInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.dicom.explorer.DiscDrive;

/**
 * Burns the disc and ejects it when done, with the system's own tools: hdiutil on macOS (it ejects
 * by itself) and the Windows burning service through burn-disc.ps1.
 */
final class DiscBurner {
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscBurner.class);

  private static final long BURN_TIMEOUT_SECONDS = 3600;

  enum Result {
    DONE,
    NO_BLANK_DISC,
    NO_BURNER,
    FAILED
  }

  private DiscBurner() {}

  static boolean isSupported() {
    return SystemInfo.isWindows || SystemInfo.isMacOS;
  }

  /** Whether a blank CD or DVD is in a drive that can burn it. */
  static Result checkBlankDisc() {
    if (SystemInfo.isMacOS) {
      List<String> out = new ArrayList<>();
      int code = runCapture(List.of("drutil", "status"), out); // NON-NLS
      if (code != 0 || out.isEmpty()) {
        return Result.NO_BURNER;
      }
      String text = String.join("\n", out).toLowerCase(Locale.ROOT);
      if (!text.contains("type:")) { // NON-NLS
        return text.contains("vendor") ? Result.NO_BLANK_DISC : Result.NO_BURNER; // NON-NLS
      }
      return text.contains("blank") ? Result.DONE : Result.NO_BLANK_DISC; // NON-NLS
    } else if (SystemInfo.isWindows) {
      return windowsScript(null, null, true);
    }
    return Result.NO_BURNER;
  }

  /**
   * Burns the disc then ejects it: the ISO image on macOS, the folder it was made from on Windows.
   */
  static Result burn(File folder, File isoImage, String label) {
    if (SystemInfo.isMacOS) {
      int code =
          DiscDrive.run(
              List.of("hdiutil", "burn", "-eject", isoImage.getPath()), // NON-NLS
              BURN_TIMEOUT_SECONDS);
      return code == 0 ? Result.DONE : Result.FAILED;
    } else if (SystemInfo.isWindows) {
      return windowsScript(folder, label, false);
    }
    return Result.NO_BURNER;
  }

  private static Result windowsScript(File folder, String label, boolean check) {
    Path script = null;
    try {
      Path dir = AppProperties.buildAccessibleTempDirectory("tmp", "burn"); // NON-NLS
      Files.createDirectories(dir);
      script = Files.createTempFile(dir, "burn-disc", ".ps1"); // NON-NLS
      try (InputStream in = DiscBurner.class.getResourceAsStream("burn-disc.ps1")) { // NON-NLS
        if (in == null) {
          return Result.FAILED;
        }
        Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
      }
      List<String> cmd = new ArrayList<>();
      cmd.add("powershell.exe"); // NON-NLS
      cmd.add("-NoProfile"); // NON-NLS
      cmd.add("-NonInteractive"); // NON-NLS
      cmd.add("-ExecutionPolicy"); // NON-NLS
      cmd.add("Bypass"); // NON-NLS
      cmd.add("-File"); // NON-NLS
      cmd.add(script.toString());
      if (check) {
        cmd.add("-Check"); // NON-NLS
      } else {
        cmd.add("-Folder"); // NON-NLS
        cmd.add(folder.getPath());
        cmd.add("-Label"); // NON-NLS
        cmd.add(label);
      }
      int code = DiscDrive.run(cmd, check ? 120 : BURN_TIMEOUT_SECONDS);
      return switch (code) {
        case 0 -> Result.DONE;
        case 3 -> Result.NO_BLANK_DISC;
        case 4 -> Result.NO_BURNER;
        default -> Result.FAILED;
      };
    } catch (IOException e) {
      LOGGER.error("Cannot run the burning script", e);
      return Result.FAILED;
    } finally {
      if (script != null) {
        try {
          Files.deleteIfExists(script);
        } catch (IOException e) {
          LOGGER.debug("Cannot delete {}", script);
        }
      }
    }
  }

  private static int runCapture(List<String> cmd, List<String> out) {
    try {
      Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      try (InputStream in = process.getInputStream()) {
        out.addAll(new String(in.readAllBytes()).lines().toList());
      }
      return process.waitFor();
    } catch (IOException e) {
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }
}
