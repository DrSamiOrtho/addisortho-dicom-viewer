/*
 * Copyright (c) 2022 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer3d.vr;

import com.jogamp.opengl.GL2ES2;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.util.GLPixelStorageModes;
import java.awt.Dimension;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JProgressBar;
import jogamp.opengl.glu.error.Error;
import org.dcm4che3.img.util.PixelDataUtils;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.opencv.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.ComboItemListener;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.util.MemoryManager;
import org.weasis.core.api.util.NativeMemoryConsumer;
import org.weasis.core.api.util.ResourceMonitor;
import org.weasis.core.api.util.SystemMemory;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.dicom.codec.*;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.dockable.SegmentationTool.Type;
import org.weasis.opencv.data.ImageCV;
import org.weasis.opencv.data.PlanarImage;

public final class VolumeBuilder {
  private static final Logger LOGGER = LoggerFactory.getLogger(VolumeBuilder.class);
  private final DicomVolTexture volTexture;
  private volatile boolean completed;
  private volatile boolean hasError;
  private volatile TextureLoader textureLoader;

  /**
   * Reports the off-heap volume staging footprint to the {@link MemoryManager} while a build runs,
   * so the image caches yield room to the 3D load.
   */
  private final NativeMemoryConsumer stagingConsumer = SystemMemory::getVolumeStagingMemory;

  public VolumeBuilder(DicomVolTexture volTexture) {
    this.volTexture = Objects.requireNonNull(volTexture);
    this.completed = false;
    this.hasError = false;
  }

  public static PlanarImage getSuitableImage(PlanarImage img) {
    int channels = CvType.channels(img.type());
    int type = CvType.depth(img.type());
    PlanarImage unsignedImage;
    if (type == CvType.CV_8U && channels > 1) {
      unsignedImage = PixelDataUtils.bgr2rgb(img);
    } else if (type == CvType.CV_16S) {
      ImageCV dstImg = new ImageCV();
      // Fix issue: glTexSubImage3D doesn't support signed short.
      // So it is set as unsigned short and shift later in shader
      img.toImageCV().convertTo(dstImg, CvType.CV_16UC(img.channels()), 1.0, 32768);
      unsignedImage = dstImg;
    } else {
      unsignedImage = img;
    }
    return unsignedImage;
  }

  public DicomVolTexture getVolTexture() {
    return volTexture;
  }

  public boolean isCompleted() {
    return completed;
  }

  public boolean isHasError() {
    return hasError;
  }

  public synchronized void start() {
    if (textureLoader == null || hasError) {
      hasError = false;
      MemoryManager.getInstance().register(stagingConsumer);
      textureLoader = new TextureLoader(this);
      textureLoader.start();
    }
  }

  public synchronized void stop() {
    TextureLoader moribund = textureLoader;
    textureLoader = null;
    completed = true;
    MemoryManager.getInstance().unregister(stagingConsumer);
    if (moribund != null) {
      moribund.interrupt();
    }
  }

  public boolean isDone() {
    return completed;
  }

  public boolean isRunning() {
    return textureLoader != null;
  }

  public void reset() {
    stop();
    completed = false;
  }

  /** Uploads the volume again into the same texture, e.g. once the smoothing was switched. */
  public synchronized void rebuild() {
    reset();
    start();
  }

  /** The pre-filter for this volume, when it is smoothed and holds one 16-bit channel. */
  private static VolumeSmoother newSmoother(DicomVolTexture volTexture) {
    int cvType = volTexture.getVolume().getCvType();
    int depth = CvType.depth(cvType);
    if (!volTexture.isSmoothing()
        || CvType.channels(cvType) != 1
        || (depth != CvType.CV_16S && depth != CvType.CV_16U)) {
      return null;
    }
    Vector3d texel = volTexture.getTexelSize();
    double sigmaXY = VolumeSmoother.SIGMA_XY;
    return new VolumeSmoother(
        volTexture.getWidth(),
        volTexture.getHeight(),
        sigmaXY,
        VolumeSmoother.sigmaZ(sigmaXY, texel.x, texel.z));
  }

  private static class TextureLoader extends Thread {
    private final VolumeBuilder volumeBuilder;

    public TextureLoader(VolumeBuilder volumeBuilder) {
      super("Texture 3D loader (OpenGL)"); // NON-NLS
      this.volumeBuilder = volumeBuilder;
    }

    public void publishVolumeInOpenGL(List<Mat> slices, int offset) {
      if (slices.isEmpty()) {
        return;
      }
      GLContext glContext = OpenglUtils.getDefaultGlContext();
      glContext.makeCurrent();
      try {
        if (isSuperseded()) {
          // A rebuild started meanwhile: its slices must not be overwritten by these stale ones.
          return;
        }
        GL2ES3 gl = glContext.getGL().getGL2ES3();
        gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, volumeBuilder.volTexture.getId());
        GLPixelStorageModes storageModes = new GLPixelStorageModes();
        // Uploads are UNPACK operations: the staging buffer holds tightly packed rows, so any width
        // whose row size is not a multiple of the default 4-byte alignment (e.g. RGB 8-bit) must
        // not be padded. Setting the pack alignment here has no effect on glTexSubImage3D.
        storageModes.setUnpackAlignment(gl, 1);
        try {
          setTexImage3DBuffer(gl, slices, offset);
        } finally {
          storageModes.restore(gl);
        }
        gl.glFinish();
      } finally {
        glContext.release();
      }
    }

    private void setTexImage3DBuffer(GL2ES3 gl, List<Mat> slices, int offset) {
      DicomVolTexture volTexture = volumeBuilder.volTexture;
      TextureSliceDataBuffer textureSliceData = TextureSliceDataBuffer.toImageData(slices);
      try {
        if (volTexture.getId() <= 0) {
          volTexture.init(gl);
        }
        // See https://docs.gl/gl4/glTexSubImage3D
        gl.glTexSubImage3D(
            GL2ES2.GL_TEXTURE_3D,
            0,
            0,
            0,
            offset,
            volTexture.getWidth(),
            volTexture.getHeight(),
            slices.size(),
            volTexture.getFormat(),
            volTexture.getType(),
            textureSliceData.buffer());
        int error;
        if ((error = gl.glGetError()) != 0) {
          LOGGER.error(
              "Cannot load volume ({} images) in OpenGL texture3D. OpenGL error: {}",
              volTexture.getDepth(),
              Error.gluErrorString(error));
          volumeBuilder.hasError = true;
          volumeBuilder.stop();
        }
      } finally {
        textureSliceData.releaseMemory();
      }
    }

    private static Mat toMat(short[] slice, DicomVolTexture volTexture) {
      Mat mat = new Mat(volTexture.getHeight(), volTexture.getWidth(), CvType.CV_16UC1);
      mat.put(0, 0, slice);
      return mat;
    }

    /**
     * Collects the slices to upload and sends them to the texture in chunks: they are staged in
     * off-heap Arena memory before the GPU upload, so a chunk is bounded by a share of physical
     * RAM, not by the JVM heap (which is unrelated to native memory).
     */
    private final class SliceStager {
      private final DicomVolTexture volTexture;
      private final long maxMemory = SystemMemory.getVolumeStagingMemory();
      private final List<Mat> slices = new ArrayList<>();
      private long sumMemory;
      private int sliceOffset;

      SliceStager(DicomVolTexture volTexture) {
        this.volTexture = volTexture;
      }

      void add(Mat slice) {
        long bytes = slice.total() * slice.elemSize();
        if (sumMemory + bytes > maxMemory) {
          flush();
          volTexture.notifyPartiallyLoaded();
        }
        slices.add(slice);
        sumMemory += bytes;
      }

      void flush() {
        Instant start = Instant.now();
        publishVolumeInOpenGL(slices, sliceOffset);
        LOGGER.debug(
            "Time to load volume ({} to {}) in OpenGL: {} ms",
            sliceOffset,
            sliceOffset + slices.size() - 1,
            Duration.between(start, Instant.now()).toMillis());
        sliceOffset += slices.size();
        slices.clear();
        sumMemory = 0;
      }
    }

    private boolean isSuperseded() {
      return volumeBuilder.textureLoader != this;
    }

    @Override
    public void run() {
      try {
        loadVolume();
      } catch (RuntimeException e) {
        if (isSuperseded()) {
          // Interrupted by a rebuild, which now owns the texture and the builder state.
          LOGGER.debug("Superseded 3D volume load ended", e);
          return;
        }
        LOGGER.error("Cannot build the 3D volume texture", e);
        volumeBuilder.hasError = true;
        volumeBuilder.stop();
        if (EventManager.getInstance().getSelectedViewPane() instanceof View3d view3d) {
          view3d.setProgressBar(null);
          GuiExecutor.execute(view3d::repaint);
        }
      }
    }

    private void loadVolume() {
      DicomVolTexture volTexture = volumeBuilder.volTexture;
      final int size = volTexture.getDepth();
      List<SpecialElementRegion> segList = null;
      ViewCanvas<DicomImageElement> view = EventManager.getInstance().getSelectedViewPane();
      ComboItemListener<Type> segType =
          EventManager.getInstance().getAction(ActionVol.SEG_TYPE).orElse(null);
      if (segType != null && segType.getSelectedItem() == Type.SEG_ONLY) {
        segList = volTexture.getSegmentations();
      }

      final JProgressBar bar;
      if (view instanceof View3d view3d) {
        bar = new JProgressBar(0, size);
        Dimension dim = new Dimension(view3d.getWidth() / 2, GuiUtils.getScaleLength(30));
        bar.setSize(dim);
        bar.setPreferredSize(dim);
        bar.setMaximumSize(dim);

        GuiExecutor.invokeAndWait(
            () -> {
              bar.setValue(0);
              bar.setStringPainted(true);
              view3d.setProgressBar(bar);
              view3d.repaint();
            });
      } else {
        bar = null;
      }

      SliceStager stager = new SliceStager(volTexture);
      VolumeSmoother smoother = newSmoother(volTexture);
      Instant timeStarted = Instant.now();

      Volume<?, ?> v = volTexture.getVolume();
      Vector3i vSize = v.getSize();
      for (int i = volTexture.depth - 1; i >= 0; i--) {
        if (isInterrupted()) {
          return;
        }
        Instant start = Instant.now();
        // Slice i is uploaded at texture depth - 1 - i; VolumeSpace shares this convention.
        int index =
            VolumeSpace.sourceSliceOfTextureSlice(
                volTexture.depth - 1 - i, volTexture.depth, vSize.z);
        PlanarImage imageMLUT = volTexture.getScaledImage(v.getAxialSlice(index));
        LOGGER.debug(
            "Time preparation of {}: {} ms", i, Duration.between(start, Instant.now()).toMillis());

        start = Instant.now();
        imageMLUT = getSuitableImage(imageMLUT);
        LOGGER.debug(
            "Time to get suitable image  {}: {} ms",
            i,
            Duration.between(start, Instant.now()).toMillis());

        if (smoother == null) {
          stager.add(imageMLUT.toMat());
        } else {
          short[] slice = new short[volTexture.getWidth() * volTexture.getHeight()];
          imageMLUT.get(0, 0, slice);
          smoother.push(slice).forEach(s -> stager.add(toMat(s, volTexture)));
        }
        if (bar != null) {
          GuiExecutor.execute(
              () -> {
                bar.setValue(bar.getValue() + 1);
                view.getJComponent().repaint();
              });
        }
      }
      if (smoother != null) {
        smoother.finish().forEach(s -> stager.add(toMat(s, volTexture)));
      }
      stager.flush();
      if (isSuperseded()) {
        return;
      }

      LOGGER.info(
          "Loading 3D texture time: {} ms",
          Duration.between(timeStarted, Instant.now()).toMillis());
      volumeBuilder.completed = true;
      MemoryManager.getInstance().unregister(volumeBuilder.stagingConsumer);
      ResourceMonitor.getInstance().recordVolume(volTexture.getDepth());

      if (view instanceof View3d view3d) {
        view3d.setProgressBar(null);
        volTexture.notifyFullyLoaded();
      }
    }
  }
}
