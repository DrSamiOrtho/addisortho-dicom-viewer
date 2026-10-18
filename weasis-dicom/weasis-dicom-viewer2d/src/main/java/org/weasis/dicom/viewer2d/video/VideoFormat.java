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
package org.weasis.dicom.viewer2d.video;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Containers of the video export, both with H.264 video: MP4 (ISO base media, plays everywhere) and
 * MOV (QuickTime), as RadiAnt offers.
 */
public enum VideoFormat {
  MP4("mp4", "MP4 video (H.264)"), // NON-NLS
  MOV("mov", "QuickTime movie (H.264)"); // NON-NLS

  private final String extension;
  private final String title;

  VideoFormat(String extension, String title) {
    this.extension = extension;
    this.title = title;
  }

  public String extension() {
    return extension;
  }

  public String title() {
    return title;
  }

  /** The file with this format's extension, added when it has another or none. */
  public Path withExtension(Path file) {
    String name = file.getFileName().toString();
    if (name.toLowerCase(Locale.ROOT).endsWith("." + extension)) {
      return file;
    }
    return file.resolveSibling(name + "." + extension);
  }

  @Override
  public String toString() {
    return title;
  }
}
