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
package org.weasis.dicom.viewer3d.vr;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.util.GLPixelStorageModes;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.ActionIcon;
import org.weasis.core.ui.util.DefaultAction;
import org.weasis.dicom.codec.DcmMediaReader;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomMediaIO;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.viewer2d.mip.MipView;
import org.weasis.dicom.viewer3d.Messages;
import org.weasis.dicom.viewer3d.vr.SecondaryCaptureBuilder.SourceInstance;

/**
 * Picture of the 3D view as rendered by OpenGL, without the Java2D annotations drawn on top (so no
 * patient name in the pixels). It can be copied to the clipboard or saved as a DICOM Secondary
 * Capture in the study of the displayed series.
 */
public final class View3dSnapshot {
  private static final Logger LOGGER = LoggerFactory.getLogger(View3dSnapshot.class);

  /** A frame read back from the GPU: interleaved RGB, top row first. */
  public record Frame(int width, int height, byte[] rgb) {

    public BufferedImage toImage() {
      BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
      int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
      for (int i = 0, j = 0; i < pixels.length; i++, j += 3) {
        pixels[i] = (rgb[j] & 0xFF) << 16 | (rgb[j + 1] & 0xFF) << 8 | rgb[j + 2] & 0xFF;
      }
      return image;
    }
  }

  private View3dSnapshot() {}

  /**
   * Export actions of the 3D view: save to the study, copy to the clipboard, and the rotation
   * video.
   */
  public static List<Action> getExportActions(View3d view) {
    return List.of(
        new DefaultAction(
            Messages.getString("3d.picture.save"),
            ResourceUtil.getIcon(ActionIcon.EXPORT_DICOM),
            _ -> saveToStudy(view)),
        new DefaultAction(
            Messages.getString("3d.picture.copy"),
            ResourceUtil.getIcon(ActionIcon.EXPORT_CLIPBOARD),
            _ -> copyToClipboard(view)),
        new DefaultAction(
            Messages.getString("view.video"),
            ResourceUtil.getIcon(ActionIcon.EXPORT_IMAGE),
            _ -> View3dVideoExport.export(view)));
  }

  /**
   * Reads back the current frame on the GL thread (the EDT for a {@code GLJPanel}). Empty when the
   * view is not showing or has nothing rendered.
   */
  public static Optional<Frame> capture(View3d view) {
    if (!view.hasValidContent()) {
      return Optional.empty();
    }
    AtomicReference<Frame> frame = new AtomicReference<>();
    view.invoke(
        true,
        drawable -> {
          GL gl = drawable.getGL();
          int width = drawable.getSurfaceWidth();
          int height = drawable.getSurfaceHeight();
          if (width > 0 && height > 0) {
            ByteBuffer rgba = Buffers.newDirectByteBuffer(width * height * 4);
            GLPixelStorageModes storageModes = new GLPixelStorageModes();
            storageModes.setPackAlignment(gl, 1);
            gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, rgba);
            storageModes.restore(gl);
            frame.set(new Frame(width, height, toFlippedRgb(rgba, width, height)));
          }
          return true;
        });
    return Optional.ofNullable(frame.get());
  }

  /**
   * Converts RGBA pixels read by {@code glReadPixels} (bottom row first) to interleaved RGB with
   * the top row first, dropping the alpha.
   */
  public static byte[] toFlippedRgb(ByteBuffer rgba, int width, int height) {
    byte[] rgb = new byte[width * height * 3];
    for (int y = 0; y < height; y++) {
      int src = (height - 1 - y) * width * 4;
      int dst = y * width * 3;
      for (int x = 0; x < width; x++, src += 4, dst += 3) {
        rgb[dst] = rgba.get(src);
        rgb[dst + 1] = rgba.get(src + 1);
        rgb[dst + 2] = rgba.get(src + 2);
      }
    }
    return rgb;
  }

  /** Saves the frame as a JPEG file chosen by the user. */
  public static void saveJpeg(View3d view) {
    Optional<Frame> frame = capture(view);
    if (frame.isEmpty()) {
      showError(view);
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(
        new FileNameExtensionFilter(Messages.getString("view.jpeg.files"), "jpg", "jpeg"));
    chooser.setSelectedFile(Path.of("3d-view.jpg").toFile()); // NON-NLS
    if (chooser.showSaveDialog(view) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path file = chooser.getSelectedFile().toPath();
    String name = file.getFileName().toString().toLowerCase();
    if (!name.endsWith(".jpg") && !name.endsWith(".jpeg")) { // NON-NLS
      file = file.resolveSibling(file.getFileName() + ".jpg"); // NON-NLS
    }
    try {
      if (!ImageIO.write(frame.get().toImage(), "jpg", Files.newOutputStream(file))) { // NON-NLS
        throw new IOException("No JPEG writer");
      }
    } catch (IOException e) {
      LOGGER.error("Cannot save the 3D picture to {}", file, e);
      JOptionPane.showMessageDialog(
          view,
          Messages.getString("view.jpeg.error"),
          Messages.getString("3d.viewer"),
          JOptionPane.WARNING_MESSAGE);
    }
  }

  public static void copyToClipboard(View3d view) {
    Optional<Frame> frame = capture(view);
    if (frame.isEmpty()) {
      showError(view);
      return;
    }
    BufferedImage image = frame.get().toImage();
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(image), null);
  }

  /**
   * Saves the frame as a Secondary Capture in the study of the displayed series and adds it to the
   * DICOM explorer, as the MPR export does. The file stays in the local cache until the user sends
   * it to an archive.
   */
  public static void saveToStudy(View3d view) {
    DicomVolTexture volTexture = view.getVolTexture();
    MediaSeries<DicomImageElement> series = volTexture == null ? null : volTexture.getSeries();
    Optional<Frame> frame = capture(view);
    Attributes source = series == null ? null : sourceAttributes(series);
    if (frame.isEmpty() || source == null) {
      showError(view);
      return;
    }
    List<SourceInstance> instances = sourceInstances(series);
    CompletableFuture.supplyAsync(() -> writeSeries(source, instances, frame.get()))
        .whenComplete(
            (picture, e) ->
                GuiExecutor.execute(
                    () -> {
                      if (e != null || picture == null) {
                        LOGGER.error("Cannot save the 3D picture", e);
                        showError(view);
                      } else {
                        addToStudy(series, picture);
                      }
                    }));
  }

  private static DicomSeries writeSeries(
      Attributes source, List<SourceInstance> instances, Frame frame) {
    Attributes dataset =
        SecondaryCaptureBuilder.build(
            source, instances, frame.width(), frame.height(), frame.rgb(), new Date());
    String seriesUID = dataset.getString(Tag.SeriesInstanceUID);
    Path dir =
        AppProperties.buildAccessibleTempDirectory(AppProperties.CACHE_NAME, "3d"); // NON-NLS
    Path file = dir.resolve(dataset.getString(Tag.SOPInstanceUID) + ".dcm"); // NON-NLS
    try {
      SecondaryCaptureBuilder.write(dataset, file);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot write " + file, e);
    }

    DicomSeries picture = new DicomSeries(seriesUID, null, DicomModel.series.tagView());
    picture.setTag(TagD.get(Tag.SeriesInstanceUID), seriesUID);
    DicomMediaIO reader = new DicomMediaIO(file);
    DicomImageElement[] elements = reader.isReadableDicom() ? reader.getMediaElement() : null;
    if (elements == null || elements.length == 0) {
      return null;
    }
    for (DicomImageElement element : elements) {
      picture.addMedia(element);
    }
    reader.writeMetaData(picture);
    return picture;
  }

  private static void addToStudy(MediaSeries<DicomImageElement> series, DicomSeries picture) {
    DataExplorerModel model = (DataExplorerModel) series.getTagValue(TagW.ExplorerModel);
    if (model instanceof DicomModel dicomModel) {
      MediaSeriesGroup study = dicomModel.getParent(series, DicomModel.study);
      MipView.openSeries(picture, model, dicomModel, study, false);
    }
  }

  private static Attributes sourceAttributes(MediaSeries<DicomImageElement> series) {
    DicomImageElement image = series.getMedia(MediaSeries.MEDIA_POSITION.FIRST, null, null);
    if (image != null && image.getMediaReader() instanceof DcmMediaReader reader) {
      return reader.getDicomObject();
    }
    return null;
  }

  private static List<SourceInstance> sourceInstances(MediaSeries<DicomImageElement> series) {
    // A multi-frame image is one instance for many elements.
    Set<SourceInstance> instances = new LinkedHashSet<>();
    for (DicomImageElement image : series.getMedias(null, null)) {
      String classUID = TagD.getTagValue(image, Tag.SOPClassUID, String.class);
      String instanceUID = TagD.getTagValue(image, Tag.SOPInstanceUID, String.class);
      if (classUID != null && instanceUID != null) {
        instances.add(new SourceInstance(classUID, instanceUID));
      }
    }
    return new ArrayList<>(instances);
  }

  private static void showError(View3d view) {
    JOptionPane.showMessageDialog(
        view,
        Messages.getString("3d.picture.error"),
        Messages.getString("3d.viewer"),
        JOptionPane.WARNING_MESSAGE);
  }

  private record ImageSelection(BufferedImage image) implements Transferable {

    @Override
    public DataFlavor[] getTransferDataFlavors() {
      return new DataFlavor[] {DataFlavor.imageFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
      return DataFlavor.imageFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
      if (!isDataFlavorSupported(flavor)) {
        throw new UnsupportedFlavorException(flavor);
      }
      return image;
    }
  }
}
