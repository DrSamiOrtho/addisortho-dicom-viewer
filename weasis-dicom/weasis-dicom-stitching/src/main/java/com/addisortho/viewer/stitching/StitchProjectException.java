/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.io.IOException;
import java.util.List;

/**
 * Why a stitch project was refused. The message is for the user and never holds a patient's name, a
 * file name or a path.
 */
final class StitchProjectException extends IOException {

  /** What kind of refusal. */
  enum Kind {
    /** Not a stitch project, or one with missing, extra or impossible parts. */
    MALFORMED,
    /** A schema version this viewer does not read. */
    UNSUPPORTED_VERSION,
    /** The checksum does not fit the contents. */
    DAMAGED,
    /** A source image is missing, changed or not the image that was stitched. */
    SOURCES,
    /** The parent version is missing or belongs elsewhere. */
    LINEAGE,
    /** A version with this identifier is already saved; versions are never replaced. */
    EXISTS,
    /** No such version is saved. */
    NOT_FOUND
  }

  private final Kind kind;
  private final transient List<String> problems;

  StitchProjectException(Kind kind, String message) {
    this(kind, message, List.of(message));
  }

  StitchProjectException(Kind kind, String message, List<String> problems) {
    super(message);
    this.kind = kind;
    this.problems = List.copyOf(problems);
  }

  Kind kind() {
    return kind;
  }

  /** Each thing that is wrong, one per source for {@link Kind#SOURCES}. */
  List<String> problems() {
    return problems;
  }
}
