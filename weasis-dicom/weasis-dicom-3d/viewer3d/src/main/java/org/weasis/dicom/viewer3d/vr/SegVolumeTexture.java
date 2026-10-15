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
import com.jogamp.opengl.GL2ES2;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL2GL3;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.util.GLPixelStorageModes;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.codec.seg.SegmentationVolume;

/**
 * A 3D OpenGL texture that stores a {@link SegmentationVolume}'s per-voxel <em>storage IDs</em> in
 * the volume's own width — {@code GL_R8UI} in byte mode, {@code GL_R16UI} once overlap combinations
 * promoted it — paired with a 1D colour lookup texture ({@code GL_RGBA8}) mapping each storage ID
 * to its (pre-blended) segment RGBA colour.
 *
 * <p>The segmentation texture is bound on texture unit {@value #SEG_TEXTURE_UNIT} and the colour
 * LUT on unit {@value #SEG_COLOR_UNIT}. These units are above the ones used by the volume renderer
 * (0=volTexture, 1=colorMap, 2=lightingMap, 3=FBO output).
 *
 * <p>Because storage IDs are discrete labels (one per segment, plus one per overlap combination),
 * the 3D texture uses {@code GL_NEAREST} filtering. The shader resolves a voxel's RGBA colour with
 * a single {@code texelFetch(segColorMap, ivec2(id, 0), 0)} — no per-bit blending at render time;
 * combination IDs already point to a pre-composited colour computed by {@link
 * SegmentationVolume#buildSegmentColorLUT()}.
 *
 * <p>With smooth surfaces on, a third texture on unit {@value #SEG_OCCUPANCY_UNIT} holds the {@link
 * SegOccupancy smoothed occupancy} of the visible segments ({@code GL_R8}, trilinear), one more
 * byte per voxel. It is built in the background through the shared context whenever the set of
 * visible storage IDs changes, and the shader keeps the label rendering until it is ready.
 */
public final class SegVolumeTexture {

  private static final Logger LOGGER = LoggerFactory.getLogger(SegVolumeTexture.class);

  /** Texture unit for the 3D segmentation storage-ID volume. */
  public static final int SEG_TEXTURE_UNIT = 4;

  /** Texture unit for the 1D segment colour lookup texture. */
  public static final int SEG_COLOR_UNIT = 5;

  /** Texture unit for the trilinear occupancy of the visible segments (smooth surfaces). */
  public static final int SEG_OCCUPANCY_UNIT = 6;

  /** Upper bound on the staging block used to batch slices into a single GL transfer. */
  private static final int MAX_UPLOAD_BLOCK_BYTES = 32 << 20;

  private final SegmentationVolume segVolume;
  private final Vector3d scale;

  private int segTextureId;
  private int segColorTextureId;
  private boolean needsUpload = true;
  private boolean needsColorUpdate = true;
  private byte[] pendingColorLut;

  /**
   * Storage width the texture was allocated with, captured in {@link #init(GL2ES2)} so that {@link
   * #uploadVolumeData(GL2ES2)} always transfers in the format the texture was created for.
   */
  private boolean shortStorage;

  private volatile boolean smoothSurfaces;

  /** Visibility per storage ID of the latest colour LUT handed to this texture. */
  private volatile boolean[] lutVisibleIds = new boolean[0];

  /** Occupancy texture in use and the visibility it was built for. */
  private volatile int occupancyTextureId;

  private volatile boolean[] occupancyVisibleIds;

  /** Visibility of the occupancy build in flight, {@code null} when none is running. */
  private boolean[] requestedVisibleIds;

  /** Bumping it abandons the occupancy build in flight. */
  private final AtomicInteger occupancyGeneration = new AtomicInteger();

  /** Replaced occupancy textures, deleted on the GL thread at the next {@link #bind(GL2ES2)}. */
  private final Queue<Integer> retiredTextures = new ConcurrentLinkedQueue<>();

  private volatile boolean destroyed;

  private volatile Runnable occupancyListener = () -> {};

  /**
   * Creates a new SegVolumeTexture.
   *
   * <p>Acquires one {@link SegmentationVolume#retain()} reference on {@code segVolume}. The
   * matching {@link SegmentationVolume#release()} is issued in {@link #destroy(GL2ES2)} so the CPU
   * buffers are kept alive exactly as long as this texture object lives.
   *
   * @param segVolume the segmentation volume to upload
   * @param scale scale factors (segVolume dims / texture dims) for volumes that were down-sampled
   *     for the GL texture. Use (1,1,1) if the segVolume matches the DicomVolTexture dimensions.
   */
  public SegVolumeTexture(SegmentationVolume segVolume, Vector3d scale) {
    this.segVolume = segVolume;
    this.scale = new Vector3d(scale);
    segVolume.retain(); // SegVolumeTexture owns one reference to the backing CPU data
  }

  public SegmentationVolume getSegVolume() {
    return segVolume;
  }

  /**
   * Initialises the OpenGL textures. Must be called from the GL thread (or while the shared context
   * is current).
   */
  public void init(GL2ES2 gl) {
    if (segTextureId <= 0) {
      IntBuffer buf = IntBuffer.allocate(2);
      gl.glGenTextures(2, buf);
      segTextureId = buf.get(0);
      segColorTextureId = buf.get(1);
    }

    int sizeX = segVolume.getSizeX();
    int sizeY = segVolume.getSizeY();
    int sizeZ = segVolume.getSizeZ();

    // ---- 3D segmentation storage-ID texture ----
    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_TEXTURE_UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segTextureId);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL2ES2.GL_TEXTURE_WRAP_R, GL.GL_CLAMP_TO_EDGE);

    // Allocate the 3D texture in the volume's own storage width: storage IDs never exceed a byte
    // (or a short once overlap combinations promote the volume), so GL_R32UI would waste 2–4× the
    // GPU memory and the same factor of upload bandwidth. usampler3D reads all three formats
    // identically, so the shaders are unaffected.
    this.shortStorage = segVolume.isShortMode();
    gl.glTexImage3D(
        GL2ES2.GL_TEXTURE_3D,
        0,
        shortStorage ? GL2ES3.GL_R16UI : GL2ES3.GL_R8UI,
        sizeX,
        sizeY,
        sizeZ,
        0,
        GL2GL3.GL_RED_INTEGER,
        shortStorage ? GL.GL_UNSIGNED_SHORT : GL.GL_UNSIGNED_BYTE,
        null);

    // ---- 1D segment colour LUT texture (RGBA8) ----
    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_COLOR_UNIT);
    gl.glBindTexture(GL.GL_TEXTURE_2D, segColorTextureId);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);

    byte[] colorLut = segVolume.buildSegmentColorLUT();
    updateVisibility(colorLut);
    // LUT width = number of allocated storage IDs (= segments + overlap combinations + slot 0
    // for background). Derived directly from the LUT byte length so it stays in sync with
    // SegmentationVolume.buildSegmentColorLUT().
    int lutWidth = Math.max(colorLut.length / 4, 1);
    gl.glTexImage2D(
        GL.GL_TEXTURE_2D,
        0,
        GL.GL_RGBA8,
        lutWidth,
        1,
        0,
        GL.GL_RGBA,
        GL.GL_UNSIGNED_BYTE,
        Buffers.newDirectByteBuffer(colorLut).rewind());

    gl.glActiveTexture(GL.GL_TEXTURE0);
    needsColorUpdate = false;
  }

  /**
   * Uploads the full SegmentationVolume storage-ID data into the 3D texture, in blocks of slices.
   * Must be called from the GL thread after {@link #init(GL2ES2)}.
   */
  public void uploadVolumeData(GL2ES2 gl) {
    if (segTextureId <= 0) {
      init(gl);
    }

    int sizeX = segVolume.getSizeX();
    int sizeY = segVolume.getSizeY();
    int sizeZ = segVolume.getSizeZ();

    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_TEXTURE_UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segTextureId);

    GLPixelStorageModes storageModes = new GLPixelStorageModes();
    // Uploads are UNPACK operations: rows of a byte texture whose width is not a multiple of 4
    // would be misread with the default alignment.
    storageModes.setUnpackAlignment(gl, 1);

    int type = shortStorage ? GL.GL_UNSIGNED_SHORT : GL.GL_UNSIGNED_BYTE;
    int sliceBytes = sizeX * sizeY * (shortStorage ? Short.BYTES : Byte.BYTES);
    // Transfer several slices per call: one glTexSubImage3D per slice costs far more in driver
    // round-trips than the memory the staging block occupies.
    int slicesPerBlock = Math.max(1, Math.min(sizeZ, MAX_UPLOAD_BLOCK_BYTES / sliceBytes));
    ByteBuffer buffer = Buffers.newDirectByteBuffer(slicesPerBlock * sliceBytes);

    // VolumeBuilder uploads the volume slices in reverse Z order (axial[depth-1] → z=0), so the seg
    // texture must mirror that reversal to stay aligned — otherwise the overlay is rendered
    // upside-down along the superior/inferior axis. Blocks are therefore packed by descending
    // source Z so that each one is contiguous in texture Z.
    long exportNanos = 0;
    long uploadNanos = 0;
    for (int texZ = 0; texZ < sizeZ; texZ += slicesPerBlock) {
      int depth = Math.min(slicesPerBlock, sizeZ - texZ);
      long start = System.nanoTime();
      buffer.clear();
      exportSlices(buffer, texZ, depth);
      buffer.flip();
      long exported = System.nanoTime();
      gl.glTexSubImage3D(
          GL2ES2.GL_TEXTURE_3D,
          0,
          0,
          0,
          texZ,
          sizeX,
          sizeY,
          depth,
          GL2GL3.GL_RED_INTEGER,
          type,
          buffer);
      exportNanos += exported - start;
      uploadNanos += System.nanoTime() - exported;
    }

    storageModes.restore(gl);
    gl.glActiveTexture(GL.GL_TEXTURE0);
    needsUpload = false;

    LOGGER.info(
        "Uploaded SegmentationVolume 3D texture: {}x{}x{} ({} segments, {} per voxel)",
        sizeX,
        sizeY,
        sizeZ,
        segVolume.getSegmentCount(),
        shortStorage ? "16 bits" : "8 bits");
    LOGGER.debug(
        "  {} ms reading the volume, {} ms in {} GL transfers",
        exportNanos / 1_000_000,
        uploadNanos / 1_000_000,
        (sizeZ + slicesPerBlock - 1) / slicesPerBlock);
  }

  /** Appends {@code depth} slices starting at texture slice {@code texZ}, in texture Z order. */
  private void exportSlices(ByteBuffer buffer, int texZ, int depth) {
    int sizeZ = segVolume.getSizeZ();
    for (int i = 0; i < depth; i++) {
      segVolume.exportSliceInto(sizeZ - 1 - (texZ + i), buffer);
    }
  }

  /**
   * Uploads the full volume data using the shared GL context (for background loading). Call from
   * any thread — this acquires the shared context internally.
   */
  public void uploadVolumeDataAsync() {
    GLContext glContext = OpenglUtils.getDefaultGlContext();
    glContext.makeCurrent();
    try {
      GL2ES2 gl = glContext.getGL().getGL2ES2();
      uploadVolumeData(gl);
      gl.glFinish();
    } finally {
      glContext.release();
    }
  }

  /**
   * Updates only the segment colour LUT texture using the SegmentationVolume's built-in attributes.
   * Cheap compared to re-uploading the entire 3D volume.
   */
  public void updateColorLUT(GL2ES2 gl) {
    updateColorLUT(gl, segVolume.buildSegmentColorLUT());
  }

  /**
   * Updates only the segment colour LUT texture with the given externally-built LUT data. This
   * allows the caller to supply visibility / opacity overrides (e.g. from the UI tree).
   *
   * @param gl the current GL context
   * @param colorLut a byte[] of size (segmentCount * 4) in RGBA8 format
   */
  public void updateColorLUT(GL2ES2 gl, byte[] colorLut) {
    if (segColorTextureId <= 0) {
      return;
    }
    int lutWidth = Math.max(colorLut.length / 4, 1);
    updateVisibility(colorLut);

    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_COLOR_UNIT);
    gl.glBindTexture(GL.GL_TEXTURE_2D, segColorTextureId);
    gl.glTexImage2D(
        GL.GL_TEXTURE_2D,
        0,
        GL.GL_RGBA8,
        lutWidth,
        1,
        0,
        GL.GL_RGBA,
        GL.GL_UNSIGNED_BYTE,
        Buffers.newDirectByteBuffer(colorLut).rewind());
    gl.glActiveTexture(GL.GL_TEXTURE0);
    needsColorUpdate = false;
  }

  /** Marks the colour LUT as needing an update on the next render pass. */
  public void setNeedsColorUpdate() {
    this.needsColorUpdate = true;
  }

  /**
   * Stores a pre-built segment colour LUT to be uploaded on the next {@link #bind(GL2ES2)} call.
   *
   * @param colorLut an RGBA8 byte[] indexed by storage ID, or {@code null} to fall back to the
   *     volume's built-in attributes
   */
  public void setPendingColorLut(byte[] colorLut) {
    this.pendingColorLut = colorLut;
    this.needsColorUpdate = true;
    // Before the next render reads isSmoothSurfaceReady(), so a hidden segment never shows through
    // an occupancy texture built for the previous visibility.
    updateVisibility(colorLut != null ? colorLut : segVolume.buildSegmentColorLUT());
  }

  /**
   * Binds the segmentation textures to their respective texture units for rendering. Call this
   * before dispatching the volume rendering shader.
   */
  public void bind(GL2ES2 gl) {
    if (segTextureId <= 0) {
      return;
    }

    deleteRetiredTextures(gl);
    if (needsUpload) {
      uploadVolumeData(gl);
    }
    if (needsColorUpdate) {
      if (pendingColorLut != null) {
        updateColorLUT(gl, pendingColorLut);
      } else {
        updateColorLUT(gl);
      }
    }

    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_TEXTURE_UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segTextureId);
    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_COLOR_UNIT);
    gl.glBindTexture(GL.GL_TEXTURE_2D, segColorTextureId);
    gl.glActiveTexture(GL.GL_TEXTURE0);
  }

  /** Returns {@code true} if the 3D texture has been successfully allocated and uploaded. */
  public boolean isReady() {
    return segTextureId > 0 && !needsUpload;
  }

  /** Visibility by storage ID as the colour LUT the user set has it, for the CPU picking. */
  public boolean[] getVisibleIds() {
    return lutVisibleIds.clone();
  }

  /** Returns the scale factors for mapping between segVolume and DicomVolTexture coordinates. */
  public Vector3d getScale() {
    return new Vector3d(scale);
  }

  /**
   * Releases all OpenGL resources and the CPU-buffer reference. Must be called from the GL thread.
   */
  public void destroy(GL2ES2 gl) {
    synchronized (this) {
      destroyed = true;
      occupancyGeneration.incrementAndGet();
      int occupancy = occupancyTextureId;
      occupancyTextureId = 0;
      if (occupancy > 0) {
        retiredTextures.add(occupancy);
      }
    }
    deleteRetiredTextures(gl);
    if (segTextureId > 0) {
      gl.glDeleteTextures(2, new int[] {segTextureId, segColorTextureId}, 0);
      segTextureId = 0;
      segColorTextureId = 0;
    }
    // Release ownership of the backing CPU data. If no other consumer retains the volume
    // (e.g. MPR was never open or has already closed), this will call removeData().
    segVolume.release();
  }

  // ---- Smooth surfaces ----

  /** Called on the EDT once a new occupancy texture is ready, typically to repaint the view. */
  public void setOccupancyListener(Runnable listener) {
    this.occupancyListener = listener == null ? () -> {} : listener;
  }

  /** Turns the smooth surfaces on (building the occupancy texture if needed) or off. */
  public synchronized void setSmoothSurfaces(boolean smooth) {
    this.smoothSurfaces = smooth;
    if (smooth) {
      requestOccupancy();
    } else {
      occupancyGeneration.incrementAndGet();
      requestedVisibleIds = null;
      int occupancy = occupancyTextureId;
      occupancyTextureId = 0;
      occupancyVisibleIds = null;
      if (occupancy > 0) {
        retiredTextures.add(occupancy); // frees the GPU memory at the next bind
      }
    }
  }

  /**
   * Returns {@code true} when the occupancy texture matches the current segment visibility, i.e.
   * when the shader may render the smooth surfaces.
   */
  public boolean isSmoothSurfaceReady() {
    return smoothSurfaces
        && occupancyTextureId > 0
        && Arrays.equals(occupancyVisibleIds, lutVisibleIds);
  }

  /**
   * Binds the occupancy texture on {@value #SEG_OCCUPANCY_UNIT}.
   *
   * @return {@code false} when there is none, so the caller binds a placeholder instead
   */
  public boolean bindOccupancy(GL2ES2 gl) {
    int occupancy = occupancyTextureId;
    if (!smoothSurfaces || occupancy <= 0) {
      return false;
    }
    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_OCCUPANCY_UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, occupancy);
    gl.glActiveTexture(GL.GL_TEXTURE0);
    return true;
  }

  private synchronized void updateVisibility(byte[] colorLut) {
    boolean[] visible = SegOccupancy.visibleIds(colorLut);
    if (!Arrays.equals(visible, lutVisibleIds)) {
      lutVisibleIds = visible;
      requestOccupancy();
    }
  }

  /** Starts an occupancy build for the current visibility unless one is ready or running. */
  private synchronized void requestOccupancy() {
    if (!smoothSurfaces || destroyed) {
      return;
    }
    boolean[] visible = lutVisibleIds;
    if (occupancyTextureId > 0 && Arrays.equals(visible, occupancyVisibleIds)) {
      if (requestedVisibleIds != null) {
        occupancyGeneration.incrementAndGet(); // back to the visibility already built
        requestedVisibleIds = null;
      }
      return;
    }
    if (Arrays.equals(visible, requestedVisibleIds) || !SegOccupancy.anyVisible(visible)) {
      return; // already building it, or nothing to show (the label rendering draws nothing too)
    }
    requestedVisibleIds = visible;
    int generation = occupancyGeneration.incrementAndGet();
    segVolume.retain(); // keeps the voxels alive if destroy() runs during the build
    CompletableFuture.supplyAsync(() -> buildOccupancyTexture(visible, generation))
        .whenComplete(
            (id, e) -> {
              segVolume.release();
              if (e != null) {
                LOGGER.error("Building the segmentation occupancy texture", e);
              }
              int textureId = id == null ? 0 : id;
              GuiExecutor.execute(() -> installOccupancy(textureId, visible, generation));
            });
  }

  /** EDT part of {@link #requestOccupancy()}: swaps the new texture in, or discards it. */
  private synchronized void installOccupancy(int textureId, boolean[] visible, int generation) {
    if (generation != occupancyGeneration.get() || destroyed || !smoothSurfaces) {
      if (textureId > 0) {
        discardTexture(textureId);
      }
      return;
    }
    requestedVisibleIds = null;
    if (textureId <= 0) {
      // Most likely out of GPU memory: keep the label rendering rather than retrying each frame.
      LOGGER.warn("Smooth segment surfaces are off: the occupancy texture could not be built");
      smoothSurfaces = false;
      return;
    }
    int old = occupancyTextureId;
    occupancyTextureId = textureId;
    occupancyVisibleIds = visible;
    if (old > 0) {
      retiredTextures.add(old);
    }
    occupancyListener.run();
  }

  private void discardTexture(int textureId) {
    if (destroyed) {
      // No further bind() will run: delete it through the shared context.
      CompletableFuture.runAsync(() -> withSharedContext(gl -> deleteTexture(gl, textureId)));
    } else {
      retiredTextures.add(textureId);
    }
  }

  private void deleteRetiredTextures(GL2ES2 gl) {
    Integer id;
    while ((id = retiredTextures.poll()) != null) {
      gl.glDeleteTextures(1, new int[] {id}, 0);
    }
  }

  /**
   * Background part of {@link #requestOccupancy()}; returns 0 when abandoned or failed. The shared
   * context is held only around each GL call, not during the distance transform: other users of
   * that context (a label upload) give up after a few seconds of waiting for it.
   */
  private int buildOccupancyTexture(boolean[] visible, int generation) {
    int sizeX = segVolume.getSizeX();
    int sizeY = segVolume.getSizeY();
    int sizeZ = segVolume.getSizeZ();
    int textureId = withSharedContext(gl -> allocateOccupancy(gl, sizeX, sizeY, sizeZ));
    if (textureId <= 0) {
      return 0;
    }
    boolean shortIds = segVolume.isShortMode();
    int sliceVoxels = sizeX * sizeY;
    int sliceBytes = sliceVoxels * (shortIds ? Short.BYTES : Byte.BYTES);
    int slicesPerBlock = Math.max(1, Math.min(sizeZ, MAX_UPLOAD_BLOCK_BYTES / sliceBytes));
    ByteBuffer labels = Buffers.newDirectByteBuffer(slicesPerBlock * sliceBytes);
    ByteBuffer occupancy = Buffers.newDirectByteBuffer(slicesPerBlock * sliceVoxels);
    long start = System.nanoTime();
    for (int texZ = 0; texZ < sizeZ; texZ += slicesPerBlock) {
      int z = texZ;
      int depth = Math.min(slicesPerBlock, sizeZ - texZ);
      boolean uploaded = false;
      if (generation == occupancyGeneration.get()) {
        labels.clear();
        exportSlices(labels, texZ, depth);
        labels.flip();
        occupancy.clear();
        SegOccupancy.fill(labels, shortIds, visible, occupancy, sizeX, sizeY, depth);
        occupancy.flip();
        uploaded =
            withSharedContext(
                    gl -> uploadOccupancy(gl, textureId, z, sizeX, sizeY, depth, occupancy))
                > 0;
      }
      if (!uploaded) {
        // Abandoned for a newer visibility, or the shared context was unavailable
        withSharedContext(gl -> deleteTexture(gl, textureId));
        return 0;
      }
    }
    LOGGER.info(
        "Built segmentation occupancy texture: {}x{}x{} ({} MB) in {} ms",
        sizeX,
        sizeY,
        sizeZ,
        (long) sliceVoxels * sizeZ >> 20,
        (System.nanoTime() - start) / 1_000_000);
    return textureId;
  }

  /** Runs {@code task} with the shared context current; returns 0 when it cannot be made so. */
  private static int withSharedContext(ToIntFunction<GL2ES2> task) {
    GLContext glContext = OpenglUtils.getDefaultGlContext();
    if (glContext.makeCurrent() == GLContext.CONTEXT_NOT_CURRENT) {
      LOGGER.error("Cannot make the shared OpenGL context current");
      return 0;
    }
    try {
      GL2ES2 gl = glContext.getGL().getGL2ES2();
      int result = task.applyAsInt(gl);
      gl.glFinish();
      return result;
    } finally {
      glContext.release();
    }
  }

  /** Allocates the occupancy texture; returns 0 when the GPU cannot (out of memory). */
  private static int allocateOccupancy(GL2ES2 gl, int sizeX, int sizeY, int sizeZ) {
    int[] ids = new int[1];
    gl.glGenTextures(1, ids, 0);
    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_OCCUPANCY_UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, ids[0]);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
    // A zero border (far outside) closes the surface of a segment that touches the volume edge.
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_S, GL2GL3.GL_CLAMP_TO_BORDER);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_T, GL2GL3.GL_CLAMP_TO_BORDER);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL2ES2.GL_TEXTURE_WRAP_R, GL2GL3.GL_CLAMP_TO_BORDER);
    gl.glTexParameterfv(
        GL2ES2.GL_TEXTURE_3D, GL2GL3.GL_TEXTURE_BORDER_COLOR, new float[] {0f, 0f, 0f, 0f}, 0);
    // Clear earlier errors so that the check below only sees the allocation.
    int pendingErrors = 0;
    while (pendingErrors < 16 && gl.glGetError() != GL.GL_NO_ERROR) {
      pendingErrors++;
    }
    gl.glTexImage3D(
        GL2ES2.GL_TEXTURE_3D,
        0,
        GL.GL_R8,
        sizeX,
        sizeY,
        sizeZ,
        0,
        GL2ES2.GL_RED,
        GL.GL_UNSIGNED_BYTE,
        null);
    boolean allocated = gl.glGetError() == GL.GL_NO_ERROR;
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, 0);
    gl.glActiveTexture(GL.GL_TEXTURE0);
    if (!allocated) {
      gl.glDeleteTextures(1, ids, 0);
      return 0;
    }
    return ids[0];
  }

  private static int uploadOccupancy(
      GL2ES2 gl, int textureId, int texZ, int sizeX, int sizeY, int depth, ByteBuffer occupancy) {
    gl.glActiveTexture(GL.GL_TEXTURE0 + SEG_OCCUPANCY_UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, textureId);
    GLPixelStorageModes storageModes = new GLPixelStorageModes();
    storageModes.setUnpackAlignment(gl, 1);
    gl.glTexSubImage3D(
        GL2ES2.GL_TEXTURE_3D,
        0,
        0,
        0,
        texZ,
        sizeX,
        sizeY,
        depth,
        GL2ES2.GL_RED,
        GL.GL_UNSIGNED_BYTE,
        occupancy);
    storageModes.restore(gl);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, 0);
    gl.glActiveTexture(GL.GL_TEXTURE0);
    return textureId;
  }

  private static int deleteTexture(GL2ES2 gl, int textureId) {
    gl.glDeleteTextures(1, new int[] {textureId}, 0);
    return 0;
  }
}
