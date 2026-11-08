/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.imp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.util.FileUtil;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.Messages;

/**
 * Opens a CD or DVD that has no DICOMDIR: copies its files into the viewer in one pass, file after
 * file as they lie on the disc, leaving out the bundled viewer program, then reads the copy. The
 * disc can come out once the copy is done.
 */
public class LoadDisc extends LoadLocalDicom {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoadDisc.class);

  private final Path disc;

  public LoadDisc(Path disc, DicomModel dicomModel) {
    super(new File[0], true, dicomModel, OpeningViewer.ALL_PATIENTS);
    this.disc = disc;
  }

  @Override
  protected File[] filesToLoad() throws IOException {
    Path dir =
        FileUtil.createTempDir(
            AppProperties.buildAccessibleTempDirectory("tmp", "disc")); // NON-NLS
    long start = System.nanoTime();
    copy(
        disc,
        dir,
        percent -> showProgress(Messages.getString("LoadDisc.copying"), percent),
        this::isCancelled);
    LOGGER.info("Copied {} in {} ms", disc, (System.nanoTime() - start) / 1_000_000);
    return new File[] {dir.toFile()};
  }

  /** Copies the files of {@code disc} that can be images into {@code dir}, in name order. */
  static void copy(Path disc, Path dir, IntConsumer progress, BooleanSupplier cancelled)
      throws IOException {
    List<Path> files;
    try (Stream<Path> walk = Files.walk(disc)) {
      files =
          walk.filter(Files::isRegularFile)
              .filter(f -> !isSkipped(disc.relativize(f).toString()))
              .sorted()
              .toList();
    }
    long total = Math.max(1, files.stream().mapToLong(LoadDisc::size).sum());
    long done = 0;
    progress.accept(0);
    for (Path file : files) {
      if (cancelled.getAsBoolean()) {
        return;
      }
      Path target = dir.resolve(disc.relativize(file).toString());
      Files.createDirectories(target.getParent());
      try {
        Files.copy(file, target);
      } catch (IOException e) {
        // A scratched sector loses one file, not the disc.
        LOGGER.warn("Cannot copy {} from the disc: {}", file, e.getMessage());
      }
      long before = done;
      done += size(file);
      if (done * 100 / total != before * 100 / total) {
        progress.accept((int) (done * 100 / total));
      }
    }
  }

  /** Never images: the bundled viewer and the disc's own DICOMDIR (already found unreadable). */
  static boolean isSkipped(String relativePath) {
    String base = Path.of(relativePath).getFileName().toString();
    return base.equalsIgnoreCase("DICOMDIR") // NON-NLS
        || base.equalsIgnoreCase("DICOMDIR.") // NON-NLS
        || DicomZipMediaIO.isSkipped(relativePath);
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }
}
