/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@link ProjectStore}'s comment asks of every store, written once and run against each: the
 * test folder store and the study library's. Interrupted and unclean writes, and a second process,
 * are tested on the library's store itself (ProjectDocumentsTest in the explorer).
 */
abstract class ProjectStoreContractTest {

  @TempDir Path dir;

  /** A store over {@link #dir}; each call a new object, as after a restart. */
  abstract ProjectStore open();

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static byte[] doc(String text) {
    return ("{\"synthetic\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void getGivesBackExactlyTheBytesPut() throws Exception {
    byte[] odd = new byte[4096];
    for (int i = 0; i < odd.length; i++) {
      odd[i] = (byte) (i * 31 + 7);
    }
    String id = id();
    open().put(StitchFixture.STUDY, id, odd);
    assertArrayEquals(odd, open().get(StitchFixture.STUDY, id));
  }

  @Test
  void aKeptVersionIsNeverReplaced() throws Exception {
    ProjectStore store = open();
    String id = id();
    store.put(StitchFixture.STUDY, id, doc("first"));

    assertThrows(
        FileAlreadyExistsException.class, () -> store.put(StitchFixture.STUDY, id, doc("second")));
    assertThrows(
        FileAlreadyExistsException.class, () -> open().put(StitchFixture.STUDY, id, doc("third")));

    assertArrayEquals(doc("first"), open().get(StitchFixture.STUDY, id));
  }

  @Test
  void callersPuttingOneIdentifierAtOnceLeaveOneWinner() throws Exception {
    ProjectStore shared = open();
    for (int round = 0; round < 10; round++) {
      String id = id();
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(6);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        byte[] mine = doc("caller " + i);
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    shared.put(StitchFixture.STUDY, id, mine);
                    return true;
                  } catch (FileAlreadyExistsException e) {
                    return false;
                  }
                }));
      }
      go.countDown();
      int winners = 0;
      for (Future<Boolean> r : results) {
        winners += r.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      pool.shutdown();
      assertEquals(1, winners, "round " + round);
    }
    assertEquals(10, open().list(StitchFixture.STUDY).size());
  }

  @Test
  void aNewStoreOverTheSameFolderListsAndReturnsEveryVersion() throws Exception {
    List<String> ids = new ArrayList<>();
    ProjectStore before = open();
    for (int i = 0; i < 7; i++) {
      String id = id();
      ids.add(id);
      before.put(StitchFixture.STUDY, id, doc("v" + i));
    }
    before.put("2.25.999", id(), doc("another study"));

    ProjectStore after = open();
    assertEquals(Set.copyOf(ids), Set.copyOf(after.list(StitchFixture.STUDY)));
    for (int i = 0; i < ids.size(); i++) {
      assertArrayEquals(doc("v" + i), after.get(StitchFixture.STUDY, ids.get(i)));
    }
  }

  @Test
  void aStudyWithNothingKeptListsNothingAndGetsNothing() throws Exception {
    assertEquals(List.of(), open().list(StitchFixture.STUDY));
    assertThrows(NoSuchFileException.class, () -> open().get(StitchFixture.STUDY, id()));
  }
}
