/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import com.addisortho.viewer.stitching.StitchProjectException.Kind;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes a {@link StitchProject} as one JSON document in UTF-8 (RFC 8259), the members
 * always in the order written here, numbers as the shortest text that reads back to the same
 * double, "not measured" as null. The same project always gives the same bytes.
 *
 * <pre>
 * {"format":"addisortho-stitch-project","schemaVersion":1,
 *  "projectId":uuid,"versionId":uuid,"parentVersionId":uuid|null,"savedAt":ISO-8601 instant,
 *  "algorithm":text,"studyInstanceUid":uid,
 *  "sources":[{"sopInstanceUid":uid,"sopClassUid":uid,"seriesInstanceUid":uid,"frame":1,
 *    "columns":int,"rows":int,"inverse":bool,
 *    "spacing":{"colMm":n,"rowMm":n,"basis":PIXEL_SPACING|MAGNIFIED|DETECTOR|SCANNER|NONE},
 *    "pixelHash":{"algorithm":"SHA-256","content":"modality-values-float32be-v1","value":hex}}],
 *  "choices":{"oneAcquisition":true,"projection":text,"position":text,
 *    "leg":RIGHT|LEFT|BOTH,"band":WHOLE|LEFT_HALF|RIGHT_HALF},
 *  "seams":[{
 *    "decision":{"kind":MATCH|MATCH_BOTH_LEGS|REFINE|POINTS|REFINE_BOTH_LEGS|POINTS_BOTH_LEGS,
 *      "band":band|null,
 *      "start":rigid|null,"points":[[fromX,fromY,toX,toY]],"movedTo":rigid|null},
 *    "cut":{"placedBy":AUTOMATIC|HAND,"requestedUpperMm":n|null},
 *    "result":{"transform":rigid,"method":automatic|semi-automatic|manual,"status":check|failed,
 *      "rmsMm":n|null,"rotationSigmaDeg":n|null,"shiftSigmaMm":n|null,"scale":n|null,
 *      "uniqueness":n|null,"holdoutMm":n|null,"matchedPoints":[[fromX,fromY,toX,toY]],
 *      "reasons":[text],"cutUpperMm":n,"cutNote":text,"cutOnStraightBone":bool}}],
 *  "blending":{"featherMm":n,"toneMm":n,"toneSigmaMm":n},
 *  "output":{"sopInstanceUid":uid,"seriesInstanceUid":uid,"unverified":bool,
 *    "mmPerPixel":n|null,"columns":int,"rows":int},
 *  "checksum":{"algorithm":"SHA-256","value":hex}}
 * rigid = {"thetaRad":n,"txMm":n,"tyMm":n}
 * </pre>
 *
 * The checksum is over the document as written without its checksum member. What each member means
 * and which may change is said on {@link StitchProject}.
 */
final class StitchProjectIO {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final List<SeamMatcher.Band> BANDS =
      List.of(SeamMatcher.Band.WHOLE, SeamMatcher.Band.LEFT_HALF, SeamMatcher.Band.RIGHT_HALF);
  private static final List<String> BAND_NAMES = List.of("WHOLE", "LEFT_HALF", "RIGHT_HALF");
  private static final String AUTOMATIC = "AUTOMATIC";
  private static final String HAND = "HAND";

  private StitchProjectIO() {}

  /** The project as its document; refuses a project that {@link StitchProject#check} refuses. */
  static byte[] write(StitchProject p) throws StitchProjectException {
    p.check();
    String body = body(p);
    String sum = sha256(body);
    return (body.substring(0, body.length() - 1)
            + ",\"checksum\":{\"algorithm\":\"SHA-256\",\"value\":\""
            + sum
            + "\"}}\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The project in the document. Refuses what is not a stitch project, any schema version but
   * {@value StitchProject#SCHEMA_VERSION}, a member it does not know or cannot find, and contents
   * that do not fit the checksum.
   */
  static StitchProject read(byte[] document) throws StitchProjectException {
    JsonNode root;
    try {
      root = MAPPER.readTree(document);
    } catch (IOException | RuntimeException e) {
      throw malformed("it is not JSON");
    }
    if (root == null || !root.isObject()) {
      throw malformed("it is not a JSON object");
    }
    JsonNode format = root.get("format");
    if (format == null || !StitchProject.FORMAT.equals(format.textValue())) {
      throw malformed("it does not say it is one");
    }
    JsonNode version = root.get("schemaVersion");
    if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
      throw malformed("its schema version is missing");
    }
    if (version.intValue() != StitchProject.SCHEMA_VERSION) {
      throw new StitchProjectException(
          Kind.UNSUPPORTED_VERSION,
          "This stitch project is schema version %d. This viewer reads version %d only, so it was"
                  .formatted(version.intValue(), StitchProject.SCHEMA_VERSION)
              + " not opened. Nothing was changed.");
    }
    Obj o = new Obj(root, "");
    o.take("format");
    o.take("schemaVersion");
    StitchProject p =
        new StitchProject(
            o.text("projectId"),
            o.text("versionId"),
            o.textOrNull("parentVersionId"),
            instant(o),
            o.text("algorithm"),
            o.text("studyInstanceUid"),
            o.list("sources", StitchProjectIO::source),
            choices(o.object("choices")),
            o.list("seams", StitchProjectIO::seam),
            blending(o.object("blending")),
            output(o.object("output")));
    Obj sum = o.object("checksum");
    String algorithm = sum.text("algorithm");
    String value = sum.text("value");
    sum.done();
    o.done();
    p.check();
    if (!"SHA-256".equals(algorithm) || !sha256(body(p)).equals(value)) {
      throw new StitchProjectException(
          Kind.DAMAGED,
          "This stitch project is damaged: its contents do not fit its checksum. It was not"
              + " opened.");
    }
    return p;
  }

  // ---- writing

  private static String body(StitchProject p) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append("{\"format\":").append(quote(StitchProject.FORMAT));
    sb.append(",\"schemaVersion\":").append(StitchProject.SCHEMA_VERSION);
    sb.append(",\"projectId\":").append(quote(p.projectId()));
    sb.append(",\"versionId\":").append(quote(p.versionId()));
    sb.append(",\"parentVersionId\":").append(quote(p.parentVersionId()));
    sb.append(",\"savedAt\":").append(quote(p.savedAt().toString()));
    sb.append(",\"algorithm\":").append(quote(p.algorithm()));
    sb.append(",\"studyInstanceUid\":").append(quote(p.studyInstanceUid()));
    sb.append(",\"sources\":[");
    for (int i = 0; i < p.sources().size(); i++) {
      source(sb.append(i == 0 ? "" : ","), p.sources().get(i));
    }
    Stitch.Choices c = p.choices();
    sb.append("],\"choices\":{\"oneAcquisition\":true");
    sb.append(",\"projection\":").append(quote(c.projection()));
    sb.append(",\"position\":").append(quote(c.position()));
    sb.append(",\"leg\":").append(quote(c.leg().name()));
    sb.append(",\"band\":").append(quote(bandName(c.band())));
    sb.append("},\"seams\":[");
    for (int i = 0; i < p.seams().size(); i++) {
      seam(sb.append(i == 0 ? "" : ","), p.seams().get(i));
    }
    StitchProject.Blending b = p.blending();
    sb.append("],\"blending\":{\"featherMm\":").append(num(b.featherMm()));
    sb.append(",\"toneMm\":").append(num(b.toneMm()));
    sb.append(",\"toneSigmaMm\":").append(num(b.toneSigmaMm()));
    StitchProject.Output out = p.output();
    sb.append("},\"output\":{\"sopInstanceUid\":").append(quote(out.sopInstanceUid()));
    sb.append(",\"seriesInstanceUid\":").append(quote(out.seriesInstanceUid()));
    sb.append(",\"unverified\":").append(out.unverified());
    sb.append(",\"mmPerPixel\":").append(num(out.mmPerPixel()));
    sb.append(",\"columns\":").append(out.columns());
    sb.append(",\"rows\":").append(out.rows());
    return sb.append("}}").toString();
  }

  private static void source(StringBuilder sb, StitchProject.Source s) {
    sb.append("{\"sopInstanceUid\":").append(quote(s.sopInstanceUid()));
    sb.append(",\"sopClassUid\":").append(quote(s.sopClassUid()));
    sb.append(",\"seriesInstanceUid\":").append(quote(s.seriesInstanceUid()));
    sb.append(",\"frame\":").append(s.frame());
    sb.append(",\"columns\":").append(s.columns());
    sb.append(",\"rows\":").append(s.rows());
    sb.append(",\"inverse\":").append(s.inverse());
    sb.append(",\"spacing\":{\"colMm\":").append(num(s.colMm()));
    sb.append(",\"rowMm\":").append(num(s.rowMm()));
    sb.append(",\"basis\":").append(quote(s.basis().name()));
    sb.append("},\"pixelHash\":{\"algorithm\":").append(quote(StitchProject.HASH_ALGORITHM));
    sb.append(",\"content\":").append(quote(StitchProject.HASH_CONTENT));
    sb.append(",\"value\":").append(quote(s.pixelHash())).append("}}");
  }

  private static void seam(StringBuilder sb, StitchProject.SeamEntry e) {
    SeamRecipe d = e.decision();
    sb.append("{\"decision\":{\"kind\":").append(quote(d.kind().name()));
    sb.append(",\"band\":").append(quote(d.band() == null ? null : bandName(d.band())));
    sb.append(",\"start\":").append(rigid(d.start()));
    sb.append(",\"points\":").append(points(d.points()));
    sb.append(",\"movedTo\":").append(rigid(d.movedTo()));
    boolean hand = !Double.isNaN(e.cutRequestedMm());
    sb.append("},\"cut\":{\"placedBy\":").append(quote(hand ? HAND : AUTOMATIC));
    sb.append(",\"requestedUpperMm\":").append(num(e.cutRequestedMm()));
    Seam r = e.result();
    sb.append("},\"result\":{\"transform\":").append(rigid(r.transform()));
    sb.append(",\"method\":").append(quote(r.method().word));
    sb.append(",\"status\":").append(quote(r.status().word));
    sb.append(",\"rmsMm\":").append(num(r.rmsMm()));
    sb.append(",\"rotationSigmaDeg\":").append(num(r.rotationSigmaDeg()));
    sb.append(",\"shiftSigmaMm\":").append(num(r.shiftSigmaMm()));
    sb.append(",\"scale\":").append(num(r.scale()));
    sb.append(",\"uniqueness\":").append(num(r.uniqueness()));
    sb.append(",\"holdoutMm\":").append(num(r.holdoutMm()));
    sb.append(",\"matchedPoints\":").append(points(r.pairs()));
    sb.append(",\"reasons\":[");
    for (int i = 0; i < r.reasons().size(); i++) {
      sb.append(i == 0 ? "" : ",").append(quote(r.reasons().get(i)));
    }
    sb.append("],\"cutUpperMm\":").append(num(e.cut().yMm()));
    sb.append(",\"cutNote\":").append(quote(e.cut().note()));
    sb.append(",\"cutOnStraightBone\":").append(e.cut().clean()).append("}}");
  }

  private static String rigid(Rigid r) {
    return r == null
        ? "null"
        : "{\"thetaRad\":%s,\"txMm\":%s,\"tyMm\":%s}"
            .formatted(num(r.theta()), num(r.tx()), num(r.ty()));
  }

  private static String points(List<Rigid.Pair> pairs) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < pairs.size(); i++) {
      Rigid.Pair p = pairs.get(i);
      sb.append(i == 0 ? "[" : ",[")
          .append(num(p.fx()))
          .append(',')
          .append(num(p.fy()))
          .append(',')
          .append(num(p.tx()))
          .append(',')
          .append(num(p.ty()))
          .append(']');
    }
    return sb.append(']').toString();
  }

  private static String bandName(SeamMatcher.Band band) {
    int i = BANDS.indexOf(band);
    if (i < 0) {
      throw new IllegalArgumentException("a band the project format does not know");
    }
    return BAND_NAMES.get(i);
  }

  // NaN is "not measured": JSON has no such number, so it is null
  private static String num(double v) {
    if (Double.isNaN(v)) {
      return "null";
    }
    if (Double.isInfinite(v)) {
      throw new IllegalArgumentException("an infinite number cannot be saved");
    }
    return Double.toString(v);
  }

  private static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format("\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    return sb.append('"').toString();
  }

  private static String sha256(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // every Java runtime has SHA-256
    }
  }

  // ---- reading

  private static StitchProjectException malformed(String problem) {
    return new StitchProjectException(Kind.MALFORMED, "Not a usable stitch project: " + problem);
  }

  private static Instant instant(Obj o) throws StitchProjectException {
    String text = o.text("savedAt");
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      throw malformed("savedAt is not a time");
    }
  }

  private static StitchProject.Source source(Obj o) throws StitchProjectException {
    String sop = o.text("sopInstanceUid");
    String sopClass = o.text("sopClassUid");
    String series = o.text("seriesInstanceUid");
    int frame = o.integer("frame");
    int columns = o.integer("columns");
    int rows = o.integer("rows");
    boolean inverse = o.bool("inverse");
    Obj spacing = o.object("spacing");
    double colMm = spacing.number("colMm");
    double rowMm = spacing.number("rowMm");
    Spacing.Basis basis = spacing.oneOf("basis", Spacing.Basis.values());
    spacing.done();
    Obj hash = o.object("pixelHash");
    if (!StitchProject.HASH_ALGORITHM.equals(hash.text("algorithm"))
        || !StitchProject.HASH_CONTENT.equals(hash.text("content"))) {
      throw malformed(hash.path + " is a kind of hash this viewer cannot check");
    }
    String value = hash.text("value");
    hash.done();
    o.done();
    return new StitchProject.Source(
        sop, sopClass, series, frame, columns, rows, inverse, colMm, rowMm, basis, value);
  }

  private static Stitch.Choices choices(Obj o) throws StitchProjectException {
    if (!o.bool("oneAcquisition")) {
      throw malformed("the pieces were not confirmed as one acquisition");
    }
    Stitch.Choices c =
        new Stitch.Choices(
            o.text("projection"),
            o.text("position"),
            o.oneOf("leg", Stitch.Leg.values()),
            band(o, "band", false));
    o.done();
    return c;
  }

  private static StitchProject.SeamEntry seam(Obj o) throws StitchProjectException {
    Obj d = o.object("decision");
    SeamRecipe.Kind kind = d.oneOf("kind", SeamRecipe.Kind.values());
    SeamMatcher.Band band = band(d, "band", true);
    Rigid start = d.rigidOrNull("start");
    List<Rigid.Pair> points = d.points("points");
    Rigid movedTo = d.rigidOrNull("movedTo");
    d.done();
    SeamRecipe decision;
    try {
      decision = new SeamRecipe(kind, band, start, points, movedTo);
    } catch (IllegalArgumentException e) {
      throw malformed(d.path + " does not fit its kind");
    }

    Obj cut = o.object("cut");
    String placedBy = cut.text("placedBy");
    double requested = cut.numberOrNaN("requestedUpperMm");
    cut.done();
    if (!(placedBy.equals(HAND) && !Double.isNaN(requested))
        && !(placedBy.equals(AUTOMATIC) && Double.isNaN(requested))) {
      throw malformed(cut.path + " must be AUTOMATIC without a height or HAND with one");
    }

    Obj r = o.object("result");
    Rigid transform = r.rigidOrNull("transform");
    Seam.Method method = null;
    String methodWord = r.text("method");
    for (Seam.Method m : Seam.Method.values()) {
      method = m.word.equals(methodWord) ? m : method;
    }
    Seam.Status status = null;
    String statusWord = r.text("status");
    for (Seam.Status s : Seam.Status.values()) {
      status = s.word.equals(statusWord) ? s : status;
    }
    if (transform == null || method == null || status == null) {
      // a status is "check" or "failed": there is no other word
      throw malformed(r.path + " has no transform, or a method or status this viewer does not use");
    }
    Seam result =
        new Seam(
            transform,
            method,
            status,
            r.numberOrNaN("rmsMm"),
            r.numberOrNaN("rotationSigmaDeg"),
            r.numberOrNaN("shiftSigmaMm"),
            r.numberOrNaN("scale"),
            r.numberOrNaN("uniqueness"),
            r.numberOrNaN("holdoutMm"),
            r.points("matchedPoints"),
            r.texts("reasons"));
    Cut.Row row =
        new Cut.Row(r.number("cutUpperMm"), r.text("cutNote"), r.bool("cutOnStraightBone"));
    r.done();
    o.done();
    return new StitchProject.SeamEntry(decision, requested, result, row);
  }

  private static StitchProject.Blending blending(Obj o) throws StitchProjectException {
    StitchProject.Blending b =
        new StitchProject.Blending(
            o.number("featherMm"), o.number("toneMm"), o.number("toneSigmaMm"));
    o.done();
    return b;
  }

  private static StitchProject.Output output(Obj o) throws StitchProjectException {
    StitchProject.Output out =
        new StitchProject.Output(
            o.text("sopInstanceUid"),
            o.text("seriesInstanceUid"),
            o.bool("unverified"),
            o.numberOrNaN("mmPerPixel"),
            o.integer("columns"),
            o.integer("rows"));
    o.done();
    return out;
  }

  private static SeamMatcher.Band band(Obj o, String name, boolean nullable)
      throws StitchProjectException {
    String text = nullable ? o.textOrNull(name) : o.text(name);
    if (text == null) {
      return null;
    }
    int i = BAND_NAMES.indexOf(text);
    if (i < 0) {
      throw malformed(o.path + name + " is not a part of the image this viewer matches on");
    }
    return BANDS.get(i);
  }

  /** Reads one member of an array. */
  @FunctionalInterface
  private interface Item<T> {
    T read(Obj o) throws StitchProjectException;
  }

  /** A JSON object whose members must all be read, each once, and none be unknown. */
  private static final class Obj {
    private final JsonNode node;
    private final String path;
    private final Set<String> taken = new HashSet<>();

    Obj(JsonNode node, String path) {
      this.node = node;
      this.path = path;
    }

    JsonNode take(String name) throws StitchProjectException {
      JsonNode v = node.get(name);
      if (v == null) {
        throw malformed(path + name + " is missing");
      }
      taken.add(name);
      return v;
    }

    void done() throws StitchProjectException {
      var names = node.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (!taken.contains(name)) {
          throw malformed("it has a member this viewer does not know (" + path + name + ")");
        }
      }
    }

    String text(String name) throws StitchProjectException {
      JsonNode v = take(name);
      if (!v.isTextual()) {
        throw malformed(path + name + " is not text");
      }
      return v.textValue();
    }

    String textOrNull(String name) throws StitchProjectException {
      return take(name).isNull() ? null : text(name);
    }

    boolean bool(String name) throws StitchProjectException {
      JsonNode v = take(name);
      if (!v.isBoolean()) {
        throw malformed(path + name + " is not true or false");
      }
      return v.booleanValue();
    }

    int integer(String name) throws StitchProjectException {
      JsonNode v = take(name);
      if (!v.isIntegralNumber() || !v.canConvertToInt()) {
        throw malformed(path + name + " is not a whole number");
      }
      return v.intValue();
    }

    double number(String name) throws StitchProjectException {
      return finite(take(name), path + name);
    }

    double numberOrNaN(String name) throws StitchProjectException {
      JsonNode v = take(name);
      return v.isNull() ? Double.NaN : finite(v, path + name);
    }

    <E extends Enum<E>> E oneOf(String name, E[] values) throws StitchProjectException {
      String text = text(name);
      for (E e : values) {
        if (e.name().equals(text)) {
          return e;
        }
      }
      throw malformed(path + name + " is a value this viewer does not know");
    }

    Obj object(String name) throws StitchProjectException {
      JsonNode v = take(name);
      if (!v.isObject()) {
        throw malformed(path + name + " is not an object");
      }
      return new Obj(v, path + name + ".");
    }

    <T> List<T> list(String name, Item<T> item) throws StitchProjectException {
      JsonNode v = array(name);
      List<T> out = new ArrayList<>();
      for (int i = 0; i < v.size(); i++) {
        if (!v.get(i).isObject()) {
          throw malformed("%s%s[%d] is not an object".formatted(path, name, i));
        }
        out.add(item.read(new Obj(v.get(i), "%s%s[%d].".formatted(path, name, i))));
      }
      return out;
    }

    List<String> texts(String name) throws StitchProjectException {
      JsonNode v = array(name);
      List<String> out = new ArrayList<>();
      for (JsonNode t : v) {
        if (!t.isTextual()) {
          throw malformed(path + name + " holds something that is not text");
        }
        out.add(t.textValue());
      }
      return out;
    }

    List<Rigid.Pair> points(String name) throws StitchProjectException {
      JsonNode v = array(name);
      List<Rigid.Pair> out = new ArrayList<>();
      for (JsonNode p : v) {
        if (!p.isArray() || p.size() != 4) {
          throw malformed(path + name + " holds a point pair that is not four numbers");
        }
        String at = path + name;
        out.add(
            new Rigid.Pair(
                finite(p.get(0), at),
                finite(p.get(1), at),
                finite(p.get(2), at),
                finite(p.get(3), at)));
      }
      return out;
    }

    Rigid rigidOrNull(String name) throws StitchProjectException {
      if (take(name).isNull()) {
        return null;
      }
      Obj r = object(name);
      Rigid rigid = new Rigid(r.number("thetaRad"), r.number("txMm"), r.number("tyMm"));
      r.done();
      return rigid;
    }

    private JsonNode array(String name) throws StitchProjectException {
      JsonNode v = take(name);
      if (!v.isArray()) {
        throw malformed(path + name + " is not a list");
      }
      return v;
    }

    private static double finite(JsonNode v, String where) throws StitchProjectException {
      if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
        throw malformed(where + " is not a number");
      }
      return v.doubleValue();
    }
  }
}
