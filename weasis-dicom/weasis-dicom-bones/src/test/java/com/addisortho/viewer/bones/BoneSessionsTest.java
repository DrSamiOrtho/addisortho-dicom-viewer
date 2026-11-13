/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoneSessionsTest {

  @Test
  void oneSessionPerCtSeries() {
    BoneSessions<Object> sessions = new BoneSessions<>();
    Object elbow = new Object();
    Object pelvis = new Object();
    GrowSession a = sessions.of("1.2.3", elbow);
    assertSame(a, sessions.of("1.2.3", elbow));
    assertNotSame(a, sessions.of("1.2.4", pelvis));
    assertEquals(2, sessions.size());
  }

  @Test
  void closedCtIsReleasedAndTheOthersStay() {
    BoneSessions<Object> sessions = new BoneSessions<>();
    Object elbow = new Object();
    Object pelvis = new Object();
    GrowSession a = sessions.of("1.2.3", elbow);
    GrowSession b = sessions.of("1.2.4", pelvis);
    Set<Object> closed = new HashSet<>();

    assertTrue(sessions.releaseIf(closed::contains).isEmpty());
    assertEquals(2, sessions.size());

    closed.add(elbow);
    assertEquals(List.of(a), sessions.releaseIf(closed::contains));
    assertFalse(sessions.holds(a));
    assertTrue(sessions.holds(b));
    assertEquals(1, sessions.size());
    // Released once only.
    assertTrue(sessions.releaseIf(closed::contains).isEmpty());
  }

  @Test
  void ctOpenedAgainAfterClosingStartsAfresh() {
    BoneSessions<Object> sessions = new BoneSessions<>();
    Object first = new Object();
    GrowSession a = sessions.of("1.2.3", first);
    sessions.releaseIf(s -> s == first);
    Object second = new Object();
    assertNotSame(a, sessions.of("1.2.3", second));
  }

  @Test
  void sessionFollowsItsCtLoadedAnewWithoutBeingClosed() {
    BoneSessions<Object> sessions = new BoneSessions<>();
    Object first = new Object();
    Object second = new Object();
    GrowSession a = sessions.of("1.2.3", first);
    // The same series under a new object while the session is alive: the bones are kept, and
    // the object that is gone no longer releases them.
    assertSame(a, sessions.of("1.2.3", second));
    assertTrue(sessions.releaseIf(s -> s == first).isEmpty());
    assertEquals(List.of(a), sessions.releaseIf(s -> s == second));
  }
}
