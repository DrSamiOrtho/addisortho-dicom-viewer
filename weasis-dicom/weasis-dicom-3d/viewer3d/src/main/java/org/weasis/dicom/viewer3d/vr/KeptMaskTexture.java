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
import com.jogamp.opengl.util.GLPixelStorageModes;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link KeptMask} on the GPU: an {@code R8} 3D texture on unit {@value #UNIT}, {@code 255}
 * where the volume is shown and {@code 0} where it is cut away, sampled with linear filtering so a
 * cut face is smooth at the half-voxel level. Slices are uploaded on the GL thread as the mask
 * changes; a 1x1x1 placeholder keeps the sampler unit complete when there is no mask.
 */
public final class KeptMaskTexture {
  private static final Logger LOGGER = LoggerFactory.getLogger(KeptMaskTexture.class);

  /** Texture unit of the mask, after the segmentation units 4 to 6. */
  public static final int UNIT = 7;

  private static final int MAX_UPLOAD_BLOCK_BYTES = 16 * 1024 * 1024;

  private int textureId;
  private int placeholderId;
  private KeptMask uploaded;
  private int uploadedGeneration = -1;

  /**
   * Binds the texture of {@code mask} on {@link #UNIT}, uploading what changed since the last
   * frame, or the placeholder when {@code mask} is {@code null}. Leaves unit 0 active.
   *
   * @return {@code true} when a mask texture is bound
   */
  public boolean bind(GL2ES2 gl, KeptMask mask) {
    if (mask == null) {
      bindPlaceholder(gl);
      return false;
    }
    if (textureId <= 0 || uploaded != mask) {
      allocate(gl, mask);
    } else if (mask.generation() != uploadedGeneration && mask.isDirty()) {
      upload(gl, mask, mask.dirtyMin(), mask.dirtyMax());
    }
    gl.glActiveTexture(GL.GL_TEXTURE0 + UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, textureId);
    gl.glActiveTexture(GL.GL_TEXTURE0);
    return true;
  }

  private void allocate(GL2ES2 gl, KeptMask mask) {
    if (textureId > 0) {
      gl.glDeleteTextures(1, new int[] {textureId}, 0);
    }
    int[] ids = new int[1];
    gl.glGenTextures(1, ids, 0);
    textureId = ids[0];
    VoxelBits bits = mask.bits();
    gl.glActiveTexture(GL.GL_TEXTURE0 + UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, textureId);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
    gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL2ES2.GL_TEXTURE_WRAP_R, GL.GL_CLAMP_TO_EDGE);
    gl.glTexImage3D(
        GL2ES2.GL_TEXTURE_3D,
        0,
        GL.GL_R8,
        bits.sizeX(),
        bits.sizeY(),
        bits.sizeZ(),
        0,
        GL2ES2.GL_RED,
        GL.GL_UNSIGNED_BYTE,
        null);
    int error = gl.glGetError();
    if (error != GL.GL_NO_ERROR) {
      LOGGER.error(
          "Cannot allocate the kept mask texture: GL error 0x{}", Integer.toHexString(error));
    }
    gl.glActiveTexture(GL.GL_TEXTURE0);
    uploaded = mask;
    upload(gl, mask, 0, bits.sizeZ() - 1);
  }

  private void upload(GL2ES2 gl, KeptMask mask, int zMin, int zMax) {
    VoxelBits bits = mask.bits();
    int sliceBytes = bits.sliceBits();
    int slicesPerBlock = Math.max(1, Math.min(bits.sizeZ(), MAX_UPLOAD_BLOCK_BYTES / sliceBytes));
    ByteBuffer buffer = Buffers.newDirectByteBuffer(slicesPerBlock * sliceBytes);
    long start = System.nanoTime();
    gl.glActiveTexture(GL.GL_TEXTURE0 + UNIT);
    gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, textureId);
    GLPixelStorageModes storageModes = new GLPixelStorageModes();
    storageModes.setUnpackAlignment(gl, 1);
    for (int z = zMin; z <= zMax; z += slicesPerBlock) {
      int depth = Math.min(slicesPerBlock, zMax - z + 1);
      buffer.clear();
      for (int i = 0; i < depth; i++) {
        bits.exportSlice(z + i, buffer);
      }
      buffer.flip();
      gl.glTexSubImage3D(
          GL2ES2.GL_TEXTURE_3D,
          0,
          0,
          0,
          z,
          bits.sizeX(),
          bits.sizeY(),
          depth,
          GL2ES2.GL_RED,
          GL.GL_UNSIGNED_BYTE,
          buffer);
    }
    storageModes.restore(gl);
    gl.glActiveTexture(GL.GL_TEXTURE0);
    uploadedGeneration = mask.generation();
    mask.clearDirty();
    LOGGER.debug(
        "Uploaded kept mask slices {} to {} in {} ms",
        zMin,
        zMax,
        (System.nanoTime() - start) / 1_000_000);
  }

  private void bindPlaceholder(GL2ES2 gl) {
    gl.glActiveTexture(GL.GL_TEXTURE0 + UNIT);
    if (placeholderId <= 0) {
      int[] ids = new int[1];
      gl.glGenTextures(1, ids, 0);
      placeholderId = ids[0];
      gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, placeholderId);
      gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
      ByteBuffer one = Buffers.newDirectByteBuffer(new byte[] {(byte) 0xFF, 0, 0, 0});
      gl.glTexImage3D(
          GL2ES2.GL_TEXTURE_3D, 0, GL.GL_R8, 1, 1, 1, 0, GL2ES2.GL_RED, GL.GL_UNSIGNED_BYTE, one);
    } else {
      gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, placeholderId);
    }
    gl.glActiveTexture(GL.GL_TEXTURE0);
  }

  /** Drops the GPU texture of the current mask; the next bind uploads it again. */
  public void invalidate() {
    uploaded = null;
    uploadedGeneration = -1;
  }

  public void destroy(GL2ES2 gl) {
    if (textureId > 0) {
      gl.glDeleteTextures(1, new int[] {textureId}, 0);
      textureId = 0;
    }
    if (placeholderId > 0) {
      gl.glDeleteTextures(1, new int[] {placeholderId}, 0);
      placeholderId = 0;
    }
    uploaded = null;
    uploadedGeneration = -1;
  }
}
