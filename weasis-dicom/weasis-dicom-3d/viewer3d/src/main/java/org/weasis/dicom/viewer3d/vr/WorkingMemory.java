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

/**
 * One budget for everything a selection allocates while it runs: the bit volumes, every flood queue
 * with its growth, and the slice cache of the sampler. A reservation beyond the limit is refused
 * with a {@link VoxelFlood.BudgetException} before the memory is taken; releases make room again,
 * so the peak is what counts.
 */
public final class WorkingMemory {

  private final long limit;
  private long used;
  private long peak;

  public WorkingMemory(long limit) {
    this.limit = limit;
  }

  /** A budget of the heap now free, less the headroom. */
  public static WorkingMemory ofFreeHeap() {
    return new WorkingMemory(VoxelFlood.availableBytes() - VoxelFlood.HEADROOM_BYTES);
  }

  public synchronized void reserve(long bytes) {
    if (used + bytes > limit) {
      throw new VoxelFlood.BudgetException(used + bytes, limit);
    }
    used += bytes;
    peak = Math.max(peak, used);
  }

  public synchronized void release(long bytes) {
    used = Math.max(0, used - bytes);
  }

  public synchronized long used() {
    return used;
  }

  /** Most bytes reserved at once so far. */
  public synchronized long peak() {
    return peak;
  }

  public long limit() {
    return limit;
  }
}
