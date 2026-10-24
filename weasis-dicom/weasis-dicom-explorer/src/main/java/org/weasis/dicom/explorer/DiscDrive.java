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
package org.weasis.dicom.explorer;

import com.formdev.flatlaf.util.SystemInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.util.StringUtil;

/**
 * The CD/DVD drive: the mounted discs, whether a path is on one, and ejecting it. Uses the tools
 * that come with the system (diskutil on macOS, the Windows shell through PowerShell, eject on
 * Linux), so nothing has to be installed.
 */
public final class DiscDrive {
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscDrive.class);

  /** File system types of a CD, DVD or Blu-ray: Windows, macOS and Linux spellings. */
  private static final Set<String> DISC_FILE_SYSTEMS = Set.of("cdfs", "udf", "cd9660", "iso9660");

  private static final String[] DICOMDIR_NAMES = {
    "DICOMDIR", "dicomdir", "DICOMDIR.", "dicomdir." // NON-NLS
  };

  private DiscDrive() {}

  /** The roots of the mounted volumes: drive letters on Windows, /Volumes on macOS. */
  public static List<File> mountedRoots() {
    List<File> roots = new ArrayList<>();
    try {
      if (SystemInfo.isWindows) {
        roots.addAll(Arrays.asList(File.listRoots()));
      } else if (SystemInfo.isMacOS) {
        addFiles(roots, new File("/Volumes"));
      } else {
        addFiles(roots, new File("/media"));
        addFiles(roots, new File("/mnt"));
        String user = System.getProperty("user.name", "local"); // NON-NLS
        addFiles(roots, new File("/media/" + user));
        addFiles(roots, new File("/run/media/" + user));
      }
    } catch (Exception e) {
      LOGGER.error("Error when reading device directories: {}", e.getMessage());
    }
    return roots;
  }

  private static void addFiles(List<File> list, File folder) {
    if (folder.canRead()) {
      File[] files = folder.listFiles();
      if (files != null) {
        Collections.addAll(list, files);
      }
    }
  }

  /** The DICOMDIR at the root of a read-only volume (a CD or DVD), or null. */
  public static File findReadOnlyDicomDir(File root) {
    if (root != null && root.canRead() && !root.isHidden()) {
      for (String s : DICOMDIR_NAMES) {
        File f = new File(root, s);
        if (f.canRead() && !f.canWrite()) {
          return f;
        }
      }
    }
    return null;
  }

  /** True when the path is on a CD, DVD or Blu-ray. */
  public static boolean isOnDisc(Path path) {
    try {
      String type = Files.getFileStore(path).type();
      return type != null && DISC_FILE_SYSTEMS.contains(type.toLowerCase(Locale.ROOT));
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** The mount point of the volume holding the path: "D:\" on Windows, "/Volumes/X" on macOS. */
  public static Path mountPoint(Path path) {
    Path p = path.toAbsolutePath();
    if (SystemInfo.isWindows) {
      return p.getRoot();
    }
    try {
      Object store = Files.getFileStore(p);
      Path last = p;
      for (Path parent = p.getParent(); parent != null; parent = parent.getParent()) {
        if (!Files.getFileStore(parent).equals(store)) {
          break;
        }
        last = parent;
      }
      return last;
    } catch (IOException e) {
      return p.getRoot();
    }
  }

  /** Ejects the disc holding the path. Returns true when the system tool reported success. */
  public static boolean eject(Path path) {
    Path mount = mountPoint(path);
    if (mount == null) {
      return false;
    }
    List<String> cmd;
    if (SystemInfo.isWindows) {
      String drive = mount.toString().replace("\\", "");
      if (!drive.matches("[A-Za-z]:")) {
        return false;
      }
      cmd =
          List.of(
              "powershell.exe", // NON-NLS
              "-NoProfile", // NON-NLS
              "-NonInteractive", // NON-NLS
              "-Command", // NON-NLS
              "(New-Object -ComObject Shell.Application).Namespace(17).ParseName('" // NON-NLS
                  + drive
                  + "').InvokeVerb('Eject')"); // NON-NLS
    } else if (SystemInfo.isMacOS) {
      cmd = List.of("diskutil", "eject", mount.toString()); // NON-NLS
    } else {
      cmd = List.of("eject", mount.toString()); // NON-NLS
    }
    return run(cmd, 120) == 0;
  }

  /**
   * Runs a system tool, logs what it prints and returns its exit code (-1 when it could not run or
   * timed out).
   */
  public static int run(List<String> cmd, long timeoutSeconds) {
    try {
      Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String out;
      try (InputStream in = process.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        LOGGER.warn("{} timed out", cmd.getFirst());
        return -1;
      }
      int code = process.exitValue();
      if (StringUtil.hasText(out)) {
        LOGGER.info("{} (exit {}): {}", cmd.getFirst(), code, out.trim());
      }
      return code;
    } catch (IOException e) {
      LOGGER.error("Cannot run {}", cmd.getFirst(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return -1;
  }
}
