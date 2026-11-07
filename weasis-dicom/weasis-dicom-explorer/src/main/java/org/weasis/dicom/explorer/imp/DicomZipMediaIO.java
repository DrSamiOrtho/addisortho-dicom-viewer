/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.imp;

import java.awt.Component;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.Timer;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.progress.ProgressMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.Codec;
import org.weasis.core.api.media.data.FileCache;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaReader;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.net.ClosableURLConnection;
import org.weasis.core.api.net.NetworkUtil;
import org.weasis.core.api.net.URIUtils;
import org.weasis.core.api.net.URLParameters;
import org.weasis.core.util.FileUtil;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadDicomDir;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.Messages;
import org.weasis.dicom.explorer.wado.LoadSeries;
import org.weasis.opencv.data.PlanarImage;

/**
 * A {@link MediaReader} that delegates loading of a DICOM ZIP archive. This allows drag-and-drop of
 * {@code .zip} files containing DICOM data to be recognized and imported automatically through the
 * codec infrastructure.
 */
public class DicomZipMediaIO implements MediaReader<MediaElement> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DicomZipMediaIO.class);

  public static final String MIME_TYPE = "application/x-zip"; // NON-NLS

  // Files a patient CD or a downloaded study often carries beside the images (a bundled viewer,
  // autorun, macOS metadata). They are never DICOM, and the bundled viewer can be most of the zip.
  private static final Set<String> SKIPPED_EXTENSIONS =
      Set.of(
          "exe", "dll", "msi", "cab", "jar", "so", "dylib", "sys", "inf", "ini", "bat", "cmd", "html",
          "htm", "js", "css", "ico", "chm", "lnk"); // NON-NLS
  private final URI uri;
  private final FileCache fileCache;
  private final DicomZipCodec codec;

  public DicomZipMediaIO(URI uri, DicomZipCodec codec) {
    this.uri = Objects.requireNonNull(uri);
    this.fileCache = new FileCache(this);
    this.codec = codec;
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public FileCache getFileCache() {
    return fileCache;
  }

  @Override
  public boolean isOnlyDelegate() {
    return true;
  }

  @Override
  public boolean delegate(DataExplorerModel explorerModel) {
    File zipFile = URIUtils.toFile(uri);
    if (zipFile.canRead() && explorerModel instanceof DicomModel dicomModel) {
      loadDicomZip(
          zipFile,
          dicomModel,
          OpeningViewer.ALL_PATIENTS,
          GuiUtils.getUICore().getApplicationWindow());
      return true;
    }
    return false;
  }

  @Override
  public MediaElement getPreview() {
    return null;
  }

  @Override
  public PlanarImage getImageFragment(MediaElement media) throws Exception {
    return null;
  }

  @Override
  public MediaElement[] getMediaElement() {
    return new MediaElement[0];
  }

  @Override
  public MediaSeries<MediaElement> getMediaSeries() {
    return null;
  }

  @Override
  public int getMediaElementNumber() {
    return 0;
  }

  @Override
  public String getMediaFragmentMimeType() {
    return MIME_TYPE;
  }

  @Override
  public Map<TagW, Object> getMediaFragmentTags(Object key) {
    return Collections.emptyMap();
  }

  @Override
  public void close() {
    // Nothing to close
  }

  @Override
  public Codec<MediaElement> getCodec() {
    return codec;
  }

  @Override
  public String[] getReaderDescription() {
    return new String[] {"DICOM ZIP reader"}; // NON-NLS
  }

  @Override
  public Object getTagValue(TagW tag) {
    return null;
  }

  @Override
  public void setTag(TagW tag, Object value) {
    // Not supported
  }

  @Override
  public boolean containTagKey(TagW tag) {
    return false;
  }

  @Override
  public void setTagNoNull(TagW tag, Object value) {
    // Not supported
  }

  @Override
  public Iterator<Entry<TagW, Object>> getTagEntrySetIterator() {
    return Collections.emptyIterator();
  }

  @Override
  public void replaceURI(URI uri) {
    // Not supported
  }

  @Override
  public boolean buildFile(File output) {
    return false;
  }

  public static void loadDicomZip(
      File file, DicomModel dicomModel, OpeningViewer openingViewer, Component parent) {
    loadDicomZip(file, dicomModel, openingViewer, parent, _ -> {});
  }

  /**
   * @param progress told the unzip percentage, 0 to 100, from the calling thread or a pool thread
   */
  public static void loadDicomZip(
      File file,
      DicomModel dicomModel,
      OpeningViewer openingViewer,
      Component parent,
      IntConsumer progress) {
    if (file != null && file.canRead()) {
      Path dir =
          FileUtil.createTempDir(
              AppProperties.buildAccessibleTempDirectory("tmp", "zip")); // NON-NLS
      try (ZipFile zipFile = new ZipFile(file)) {
        if (zipFile.isEncrypted()) {
          JPanel panel = new JPanel();
          JPasswordField pass = new JPasswordField(16);
          panel.add(new JLabel(Messages.getString("enter.password")));
          panel.add(pass);
          ActionListener al = ae -> pass.requestFocusInWindow();
          Timer timer = new Timer(250, al);
          timer.setRepeats(false);
          timer.start();
          int response =
              JOptionPane.showOptionDialog(
                  parent,
                  panel,
                  Messages.getString("DicomZipImport.title"),
                  JOptionPane.OK_CANCEL_OPTION,
                  JOptionPane.PLAIN_MESSAGE,
                  null,
                  null,
                  pass);
          if (response == JOptionPane.OK_OPTION) {
            zipFile.setPassword(pass.getPassword());
          }
        }
        long start = System.nanoTime();
        if (zipFile.isEncrypted() || !extractFast(file, dir, progress)) {
          extractWithZip4j(zipFile, dir, progress);
        }
        LOGGER.info(
            "Unzipped {} in {} ms", file.getName(), (System.nanoTime() - start) / 1_000_000);
      } catch (IOException e) {
        LOGGER.error("unzipping", e);
      }
      File dicomdir = new File(dir.toFile(), "DICOMDIR");
      if (dicomdir.canRead()) {
        DicomDirLoader dirImport = new DicomDirLoader(dicomdir, dicomModel, false);
        List<LoadSeries> loadSeries = dirImport.readDicomDir();
        if (loadSeries != null && !loadSeries.isEmpty()) {
          DicomModel.LOADING_EXECUTOR.execute(
              new LoadDicomDir(loadSeries, dicomModel, openingViewer));
        } else {
          LOGGER.error("Cannot import DICOM from {}", file);
        }
      } else {
        LoadLocalDicom dicom =
            new LoadLocalDicom(new File[] {dir.toFile()}, true, dicomModel, openingViewer);
        DicomModel.LOADING_EXECUTOR.execute(dicom);
      }
    }
  }

  static boolean isSkipped(String entryName) {
    String name = entryName.replace('\\', '/');
    if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) { // NON-NLS
      return true;
    }
    String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (base.equals(".ds_store") || base.equals("thumbs.db") || base.startsWith("._")) { // NON-NLS
      return true;
    }
    int dot = base.lastIndexOf('.');
    return dot > 0 && SKIPPED_EXTENSIONS.contains(base.substring(dot + 1));
  }

  /**
   * Unzips with the JDK reader, several entries at once, leaving out files that are never images.
   * zip4j unzips one entry at a time, which is what made large studies slow to open.
   *
   * @return false when the archive could not be read this way, so the caller falls back to zip4j
   */
  static boolean extractFast(File file, Path dir, IntConsumer progress) {
    Path root = dir.toAbsolutePath().normalize();
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
      List<? extends ZipEntry> entries =
          zip.stream().filter(e -> !e.isDirectory() && !isSkipped(e.getName())).toList();
      // Progress by bytes written; an entry of unknown size counts as one byte.
      long total = entries.stream().mapToLong(e -> Math.max(e.getSize(), 1)).sum();
      AtomicLong done = new AtomicLong();
      progress.accept(0);
      int threads = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 4);
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      try {
        List<Future<?>> jobs = new ArrayList<>(entries.size());
        for (ZipEntry entry : entries) {
          Path target = root.resolve(entry.getName()).normalize();
          if (!target.startsWith(root) || target.equals(root)) {
            LOGGER.warn("Skipping zip entry outside the target folder: {}", entry.getName());
            continue;
          }
          jobs.add(
              pool.submit(
                  () -> {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    long before = done.getAndAdd(Math.max(entry.getSize(), 1));
                    long after = before + Math.max(entry.getSize(), 1);
                    if (after * 100 / total != before * 100 / total) {
                      progress.accept((int) (after * 100 / total));
                    }
                    return null;
                  }));
        }
        for (Future<?> job : jobs) {
          job.get();
        }
        return true;
      } catch (ExecutionException e) {
        LOGGER.info("Fast unzip failed, using zip4j: {}", e.getCause().getMessage());
        return false;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        pool.shutdownNow();
      }
    } catch (IOException | RuntimeException e) {
      LOGGER.info("Fast unzip failed, using zip4j: {}", e.getMessage());
      return false;
    }
  }

  /** zip4j handles what the JDK reader cannot, such as password-protected zips. */
  private static void extractWithZip4j(ZipFile zipFile, Path dir, IntConsumer progress)
      throws IOException {
    zipFile.setRunInThread(true);
    zipFile.extractAll(dir.toString());
    ProgressMonitor monitor = zipFile.getProgressMonitor();
    try {
      while (monitor.getState() == ProgressMonitor.State.BUSY) {
        progress.accept(monitor.getPercentDone());
        Thread.sleep(200);
      }
    } catch (InterruptedException e) {
      monitor.setCancelAllTasks(true);
      Thread.currentThread().interrupt();
    }
    if (monitor.getException() != null) {
      throw new IOException(monitor.getException());
    }
  }

  public static void loadDicomZip(String uri, DicomModel dicomModel) {
    if (StringUtil.hasText(uri)) {
      File zipFile = null;
      try {
        URI u = new URI(uri);
        if ("file".equals(u.getScheme())) { // NON-NLS
          zipFile = URIUtils.toFile(u);
        } else {
          zipFile =
              Files.createTempFile(AppProperties.APP_TEMP_DIR, "dicom_", ".zip")
                  .toFile(); // NON-NLS

          ClosableURLConnection urlConnection =
              NetworkUtil.getUrlConnection(u.toURL(), URLParameters.DEFAULT);
          FileUtil.writeStreamWithIOException(urlConnection.getInputStream(), zipFile.toPath());
        }
      } catch (Exception e) {
        LOGGER.error("Loading DICOM Zip", e);
      }
      OpeningViewer openingViewer = OpeningViewer.ALL_PATIENTS;
      loadDicomZip(zipFile, dicomModel, openingViewer, GuiUtils.getUICore().getApplicationWindow());
    }
  }
}
