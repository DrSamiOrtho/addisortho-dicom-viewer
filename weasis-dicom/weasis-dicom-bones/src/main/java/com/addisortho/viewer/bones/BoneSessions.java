/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The bone sessions of the open CTs, one per CT series, each with the series it belongs to. A
 * session holds the masks of its bones and the steps of its history, tens of megabytes on a large
 * CT, so it is let go when its CT is closed: {@link #releaseIf} hands back the sessions whose
 * series is gone for the caller to free what hangs on them (the live label volume, a pending
 * write).
 *
 * <p>A CT opened again after being closed starts a new session, as a CT opened for the first time
 * does; its bones written as a DICOM SEG are not touched.
 *
 * @param <S> what tells an open CT from a closed one: the series of the explorer
 */
final class BoneSessions<S> {

  private record Entry<S>(GrowSession session, S series) {}

  private final Map<String, Entry<S>> bySeriesUID = new HashMap<>();

  /**
   * The session of a CT series, made on first use. A series object other than the one the session
   * was made for is the same CT loaded anew: the session follows it, so it is released with the
   * series now open and not with the one already gone.
   */
  GrowSession of(String seriesUID, S series) {
    Entry<S> entry = bySeriesUID.get(seriesUID);
    if (entry == null || entry.series() != series) {
      GrowSession session = entry == null ? new GrowSession(seriesUID) : entry.session();
      entry = new Entry<>(session, series);
      bySeriesUID.put(seriesUID, entry);
    }
    return entry.session();
  }

  /** Whether the session is still one of an open CT. */
  boolean holds(GrowSession session) {
    for (Entry<S> entry : bySeriesUID.values()) {
      if (entry.session() == session) {
        return true;
      }
    }
    return false;
  }

  int size() {
    return bySeriesUID.size();
  }

  /** Forgets the sessions whose series is closed and returns them. */
  List<GrowSession> releaseIf(Predicate<S> closed) {
    List<GrowSession> released = new ArrayList<>();
    Iterator<Entry<S>> it = bySeriesUID.values().iterator();
    while (it.hasNext()) {
      Entry<S> entry = it.next();
      if (closed.test(entry.series())) {
        released.add(entry.session());
        it.remove();
      }
    }
    return released;
  }
}
