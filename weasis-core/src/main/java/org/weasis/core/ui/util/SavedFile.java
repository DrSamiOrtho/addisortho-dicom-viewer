/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.Messages;

/**
 * Writes a file so that a half-written one never carries the real name: the content goes to a
 * temporary name beside the destination, is checked, and only then takes the real name.
 */
public final class SavedFile {
  private static final Logger LOGGER = LoggerFactory.getLogger(SavedFile.class);
  private static final int LONGEST_EXTENSION = 6;

  private SavedFile() {}

  /** Writes the content into the path it is given. */
  @FunctionalInterface
  public interface Writer {
    /**
     * @return false when the content could not be written
     */
    boolean write(Path staged) throws IOException;
  }

  /**
   * A temporary name in the destination's folder. Short, so a long patient name cannot push it past
   * the file system's limit, and with the same extension, which picks the image encoder.
   */
  public static Path stagingPath(Path destination) {
    String name = destination.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String extension =
        dot > 0 && name.length() - dot <= LONGEST_EXTENSION ? name.substring(dot) : "";
    String id = UUID.randomUUID().toString().substring(0, 8);
    return destination.resolveSibling(".saving-" + id + extension);
  }

  /**
   * Gives a finished temporary file the real name, replacing an earlier export of the same file.
   */
  public static void publish(Path staged, Path destination) throws IOException {
    if (!Files.isRegularFile(staged) || Files.size(staged) == 0) {
      throw new IOException("The file was not written: " + destination.getFileName());
    }
    long size = Files.size(staged);
    try {
      Files.move(
          staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
    }
    if (Files.size(destination) != size) {
      throw new IOException("The saved file is incomplete: " + destination.getFileName());
    }
  }

  /**
   * Creates the destination's folder, writes the file and publishes it. Throws when the file did
   * not end up complete at the destination, and leaves no temporary file behind either way.
   */
  public static void write(Path destination, Writer writer) throws IOException {
    Path parent = destination.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path staged = stagingPath(destination);
    try {
      if (!writer.write(staged)) {
        throw new IOException("The file could not be written: " + destination.getFileName());
      }
      publish(staged, destination);
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  /** Why a save failed, in words the user can act on. */
  public static String reason(Throwable error) {
    if (error instanceof AccessDeniedException) {
      return Messages.getString("save.no.permission");
    }
    if (error instanceof FileSystemException e && e.getReason() != null) {
      return e.getReason();
    }
    return error.getMessage() == null ? error.toString() : error.getMessage();
  }

  /**
   * Tells the user where their work is, with a button that opens the folder.
   *
   * @param folder the folder to offer, or null when it cannot be opened (a disc that was ejected)
   * @param messageType a {@link JOptionPane} message type
   */
  public static void showSaved(
      Component parent, String title, String message, File folder, int messageType) {
    if (folder == null || !canOpenFolders()) {
      JOptionPane.showMessageDialog(parent, message, title, messageType);
      return;
    }
    String open = Messages.getString("open.folder");
    String ok = UIManager.getString("OptionPane.okButtonText");
    Object[] options = {open, ok};
    int choice =
        JOptionPane.showOptionDialog(
            parent, message, title, JOptionPane.DEFAULT_OPTION, messageType, null, options, ok);
    if (choice == 0) {
      try {
        Desktop.getDesktop().open(folder);
      } catch (IOException | RuntimeException e) {
        LOGGER.error("Cannot open the folder: {}", e.getClass().getSimpleName());
        JOptionPane.showMessageDialog(
            parent, folder.getPath(), Messages.getString("open.folder"), JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private static boolean canOpenFolders() {
    return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
  }
}
