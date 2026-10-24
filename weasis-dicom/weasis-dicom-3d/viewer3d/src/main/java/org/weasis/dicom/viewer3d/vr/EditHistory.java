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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * The undo and redo history of what was taken away from the 3D volume, in the order it was done:
 * crop box changes (box or free-hand) and scalpel or keep/remove cuts, so Ctrl+Z and Ctrl+Y walk
 * back and forth through all of them, many steps deep.
 *
 * <p>The cuts keep their voxels in the {@link KeptMask}, which has its own stack of differences; a
 * {@link #cut} entry only moves that stack one step, so both stay in step as long as every cut goes
 * through this history. Used on the event dispatch thread.
 */
public final class EditHistory {

  /** Longest history kept, the same as the mask's, so a cut entry always finds its difference. */
  public static final int MAX_STEPS = KeptMask.MAX_HISTORY;

  /** One step that can be taken back and done again. */
  public interface Edit {
    /**
     * @return {@code false} when the step can no longer be undone (it is then dropped)
     */
    boolean undo();

    /**
     * @return {@code false} when the step can no longer be redone (it is then dropped)
     */
    boolean redo();
  }

  /** A crop box state: on or off, and the box. */
  public record CropState(boolean enabled, CropBox box) {}

  private final Deque<Edit> undo = new ArrayDeque<>();
  private final Deque<Edit> redo = new ArrayDeque<>();
  private final List<Runnable> listeners = new ArrayList<>();

  /**
   * Called after every change of the history: a step done, undone, redone or the history cleared.
   */
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void changed() {
    for (Runnable l : List.copyOf(listeners)) {
      l.run();
    }
  }

  /** A step done just now: it becomes the last one to undo, and the redo steps are forgotten. */
  public void push(Edit edit) {
    undo.push(edit);
    if (undo.size() > MAX_STEPS) {
      undo.removeLast();
    }
    redo.clear();
    changed();
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public int undoSteps() {
    return undo.size();
  }

  public int redoSteps() {
    return redo.size();
  }

  /** Takes back the last step; one that cannot be taken back is dropped and the next one tried. */
  public boolean undo() {
    Edit edit;
    while ((edit = undo.poll()) != null) {
      if (edit.undo()) {
        redo.push(edit);
        changed();
        return true;
      }
    }
    changed();
    return false;
  }

  /** Does again the last step taken back. */
  public boolean redo() {
    Edit edit;
    while ((edit = redo.poll()) != null) {
      if (edit.redo()) {
        undo.push(edit);
        changed();
        return true;
      }
    }
    changed();
    return false;
  }

  public void clear() {
    undo.clear();
    redo.clear();
    changed();
  }

  /** A cut already applied to {@code mask}: undo and redo move the mask's own stack. */
  public static Edit cut(KeptMask mask) {
    return new Edit() {
      @Override
      public boolean undo() {
        return mask.undo();
      }

      @Override
      public boolean redo() {
        return mask.redo();
      }
    };
  }

  /** A crop box change from {@code before} to {@code after}, applied again by {@code apply}. */
  public static Edit crop(CropState before, CropState after, Consumer<CropState> apply) {
    return new Edit() {
      @Override
      public boolean undo() {
        apply.accept(before);
        return true;
      }

      @Override
      public boolean redo() {
        apply.accept(after);
        return true;
      }
    };
  }

  /** Several steps made as one, undone in reverse order. */
  public static Edit group(List<Edit> edits) {
    List<Edit> steps = List.copyOf(edits);
    return new Edit() {
      @Override
      public boolean undo() {
        boolean any = false;
        for (Edit e : steps.reversed()) {
          any |= e.undo();
        }
        return any;
      }

      @Override
      public boolean redo() {
        boolean any = false;
        for (Edit e : steps) {
          any |= e.redo();
        }
        return any;
      }
    };
  }

  /** Collects the steps of a {@link #group}; check {@link #isEmpty()} before building. */
  public static final class Group {
    private final List<Edit> edits = new ArrayList<>();

    public Group add(Edit edit) {
      edits.add(edit);
      return this;
    }

    public boolean isEmpty() {
      return edits.isEmpty();
    }

    public Edit build() {
      return edits.size() == 1 ? edits.getFirst() : group(edits);
    }
  }
}
