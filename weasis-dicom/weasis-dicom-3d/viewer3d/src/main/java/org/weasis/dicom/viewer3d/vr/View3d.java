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

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES2;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL2GL3;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import org.dcm4che3.img.lut.PresetWindowLevel;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4d;
import org.weasis.core.api.gui.util.ActionState;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.ComboItemListener;
import org.weasis.core.api.gui.util.Feature;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.MouseActionAdapter;
import org.weasis.core.api.image.OpManager;
import org.weasis.core.api.image.ZoomOp.Interpolation;
import org.weasis.core.api.image.util.ImageLayer;
import org.weasis.core.api.image.util.MeasurableLayer;
import org.weasis.core.api.image.util.Unit;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.service.AuditLog;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.api.util.FontItem;
import org.weasis.core.api.util.FontTools;
import org.weasis.core.ui.editor.image.*;
import org.weasis.core.ui.editor.image.DefaultView2d.ZoomType;
import org.weasis.core.ui.model.graphic.Graphic;
import org.weasis.core.ui.model.graphic.imp.seg.SegRegion;
import org.weasis.core.ui.model.layer.LayerAnnotation;
import org.weasis.core.ui.model.layer.LayerItem;
import org.weasis.core.ui.model.layer.LayerType;
import org.weasis.core.ui.model.utils.bean.PanPoint;
import org.weasis.core.util.LangUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.SpecialElementRegion;
import org.weasis.dicom.codec.geometry.PatientOrientation;
import org.weasis.dicom.codec.seg.SegBuildScheduler;
import org.weasis.dicom.codec.seg.SegSpecialElement;
import org.weasis.dicom.codec.seg.SegmentationVolume;
import org.weasis.dicom.explorer.DicomSeriesHandler;
import org.weasis.dicom.viewer2d.mpr.AxisDirection;
import org.weasis.dicom.viewer2d.mpr.MprContainer;
import org.weasis.dicom.viewer2d.mpr.MprFactory;
import org.weasis.dicom.viewer2d.mpr.OriginalStack;
import org.weasis.dicom.viewer2d.mpr.SegVolumeBuilder;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.InfoLayer3d;
import org.weasis.dicom.viewer3d.Messages;
import org.weasis.dicom.viewer3d.OpenGLInfo;
import org.weasis.dicom.viewer3d.Shortcuts3d;
import org.weasis.dicom.viewer3d.View3DFactory;
import org.weasis.dicom.viewer3d.dockable.SegmentationTool.Type;
import org.weasis.dicom.viewer3d.geometry.Axis;
import org.weasis.dicom.viewer3d.geometry.Camera;
import org.weasis.dicom.viewer3d.geometry.MouseToolListener;
import org.weasis.dicom.viewer3d.geometry.View;
import org.weasis.dicom.viewer3d.vr.Annotations3d.Item;
import org.weasis.dicom.viewer3d.vr.Annotations3d.Kind;
import org.weasis.dicom.viewer3d.vr.TextureData.PixelFormat;
import org.weasis.dicom.viewer3d.vr.VolumePicker.Hit;
import org.weasis.opencv.data.PlanarImage;
import org.weasis.opencv.op.lut.LutShape;
import org.weasis.opencv.seg.RegionAttributes;

public class View3d extends VolumeCanvas
    implements ViewCanvas<DicomImageElement>,
        RenderingLayerChangeListener<DicomImageElement>,
        GLEventListener,
        ViewProgress {

  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(View3d.class);

  static final float[] vertexBufferData =
      new float[] {
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        -1.0f, 1.0f,
        1.0f, -1.0f,
        1.0f, 1.0f
      };

  protected final FocusHandler<DicomImageElement> focusHandler;
  protected final GraphicMouseHandler<DicomImageElement> graphicMouseHandler;

  private int pointerType = 0;
  private final LayerAnnotation infoLayer;
  protected final ContextMenuHandler<DicomImageElement> contextMenuHandler;

  private final TextureData texture;
  private final Program program;
  private final Program quadProgram;
  private final boolean useComputeShader;
  protected final RenderingLayer<DicomImageElement> renderingLayer;

  private int vertexBuffer;
  protected Preset volumePreset;
  private JProgressBar progressBar;

  private volatile Vector3d mprCrossHairPosition; // NOSONAR visibility reference
  private volatile Quaterniond mprCrossHairRotation; // NOSONAR visibility reference
  private volatile CrosshairCutMode mprCrossHairCutMode = CrosshairCutMode.NONE;

  private PropertyChangeListener mprCrossHairListener;
  private DicomVolTexture mprCrossHairListenerSource;

  /** Segmentation overlay 3D texture — null when no segmentation is active. */
  private volatile SegVolumeTexture segVolumeTexture; // NOSONAR visibility reference

  /** Frame-time breakdown, inert unless {@link RenderProfiler#P_PROFILE} is set. */
  private final RenderProfiler profiler = new RenderProfiler(this);

  /** 1×1×1 placeholders keeping the segmentation sampler units complete, see bindSegTextures. */
  private int segPlaceholderTextureId;

  private int segPlaceholderColorId;

  /**
   * The SEG files contained in the current segmentation texture, each with the segment-number
   * offset applied when several files were merged into one volume — see {@link #mergeSegVolumes}.
   * Consumed by {@link #buildRegionOverrideMap()} so the colour LUT overrides target the shifted
   * IDs and so unchecked files are always forced invisible.
   */
  private volatile List<RenderedSeg> renderedSegs = List.of(); // NOSONAR visibility ref

  /** Per-view segmentation display mode — applied through the synchronization mechanism. */
  private volatile Type segType = Type.NONE;

  /** Generation stamp; bumping it invalidates any in-flight asynchronous seg texture build. */
  private final AtomicInteger segBuildGeneration = new AtomicInteger();

  /** {@code true} while an asynchronous seg texture build is running for this view. */
  private volatile boolean segBuildRunning;

  /** Renders the segments from their trilinear occupancy rather than the nearest label voxel. */
  private volatile boolean segSmooth = true;

  /** 1×1×1 placeholder keeping the occupancy sampler unit complete, see bindSegTextures. */
  private int segOccupancyPlaceholderId;

  private volatile boolean cropEnabled;

  private volatile CropBox cropBox = CropBox.FULL;

  /** Crop box changes and cuts, undone and redone in the order they were made. */
  private final EditHistory editHistory = new EditHistory();

  /** The crop box before a slider drag began, recorded as one step when the drag ends. */
  private EditHistory.CropState cropBeforeAdjust;

  /** The volume the crop box was last fitted to the bone for, see {@link #autoCropToBone}. */
  private DicomVolTexture autoCroppedTexture;

  /** Name of the preset a CT opens with, see {@link #defaultPresetFor}. */
  public static final String CT_DEFAULT_PRESET = "AO Bone"; // NON-NLS

  /** Lowest opacity at which a voxel counts as visible for the keep/remove selection. */
  private static final double VISIBLE_OPACITY = 0.02;

  /** Erosion depth of the light separation of keep/remove, in grid voxels. */
  private static final int SEPARATION_VOXELS = 2;

  /** The cuts run one at a time, off the event dispatch thread. */
  private static final ExecutorService CUT_EXECUTOR =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "3D cuts"); // NON-NLS
            t.setDaemon(true);
            return t;
          });

  /** Voxels left by the scalpel and keep/remove cuts; {@code null} until the first cut. */
  private volatile KeptMask keptMask; // NOSONAR visibility reference

  private final KeptMaskTexture keptMaskTexture = new KeptMaskTexture();
  private final Lasso lasso = new Lasso();
  private final Annotations3d annotations = new Annotations3d();
  private final AltLassoHandler altLassoHandler = new AltLassoHandler();
  private final List<ViewSnapshot> snapshots = new ArrayList<>();
  private int snapshotIndex = -1;
  private Vector3d lengthStart;
  private Vector3d arrowStartTex;
  private double arrowStartDepth;
  private Point arrowDragPoint;
  private volatile boolean cutRunning;
  private volatile String statusMessage;
  private Timer statusTimer;

  /** The texture field of the shown volume, kept while its texture and smoothing stay the same. */
  private TextureField sharedField;

  private FieldKey sharedFieldKey;

  /** Picks run here, one at a time, off the event dispatch thread. */
  private static final ExecutorService PICK_EXECUTOR =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "3D picking"); // NON-NLS
            t.setDaemon(true);
            return t;
          });

  /** The pick waiting to start, if any; a newer request replaces it (drag coalescing). */
  private final java.util.concurrent.atomic.AtomicReference<PickRequest> queuedPick =
      new java.util.concurrent.atomic.AtomicReference<>();

  private volatile PickRequest runningPick;
  private Timer movieTimer;
  private boolean lightSeparation;

  /**
   * Per-view auto-sync button rendered as a corner overlay. Lazily created the first time {@link
   * #updateSynchState()} is invoked, so it picks up the per-view {@link SynchData} attached by
   * {@link org.weasis.dicom.viewer3d.EventManager#updateAllListeners}.
   */
  private SynchViewButton synchButton;

  public View3d(
      ImageViewerEventManager<DicomImageElement> eventManager, DicomVolTexture volTexture) {
    super(eventManager, volTexture, null);
    // Detect whether compute shaders are available (OpenGL >= 4.3).
    OpenGLInfo glInfo = View3DFactory.getOpenGLInfo();
    this.useComputeShader =
        !View3DFactory.isFboForced() && (glInfo == null || glInfo.isComputeShaderCapable());

    if (useComputeShader) {
      this.texture = new ComputeTexture(this, ComputeTexture.COMPUTE_LOCAL_SIZE);
      this.program = new Program("compute", ShaderManager.COMPUTE_SHADER); // NON-NLS
      LOGGER.info("Volume rendering: using compute shader path (OpenGL >= 4.3)");
    } else {
      this.texture = new FboRenderTexture(this);
      this.program =
          new Program(
              "fbo", ShaderManager.FBO_VERTEX_SHADER, ShaderManager.FBO_FRAGMENT_SHADER); // NON-NLS
      LOGGER.info(
          "Volume rendering: using FBO fragment-shader path (OpenGL 3.3 fallback{})",
          View3DFactory.isFboForced()
              ? ", forced via " + View3DFactory.P_FORCE_FBO
              : ""); // NON-NLS
    }
    this.quadProgram =
        new Program("basic", ShaderManager.VERTEX_SHADER, ShaderManager.FRAGMENT_SHADER); // NON-NLS
    try {
      setSharedContext(OpenglUtils.getDefaultGlContext());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    setLayout(null);

    this.renderingLayer = new RenderingLayer<>();
    this.volumePreset = Preset.getDefaultPreset(null);
    volumePreset.setRequiredBuilding(true);

    actionsInView.put(ActionVol.ORIENTATION_CUBE.cmd(), false);

    this.infoLayer = new InfoLayer3d(this);

    initActionWState();

    this.graphicMouseHandler = new GraphicMouseHandler<>(this);
    this.contextMenuHandler = new ContextMenuHandler<>(this);
    this.focusHandler = new FocusHandler<>(this);
    setFocusable(true);

    setBorder(viewBorder);
    setPreferredSize(new Dimension(4096, 4096));
    setMinimumSize(new Dimension(50, 50));

    addGLEventListener(this);
  }

  protected void initActionWState() {
    // set unit when texture load (middle image)
    actionsInView.put(ActionW.SPATIAL_UNIT.cmd(), Unit.PIXEL);
    actionsInView.put(ZOOM_TYPE_CMD, ZoomType.BEST_FIT);
    actionsInView.put(ActionW.ZOOM.cmd(), 0.0);
    actionsInView.put(ActionW.LENS.cmd(), false);
    actionsInView.put(ActionW.DRAWINGS.cmd(), true);
    actionsInView.put(LayerType.CROSSLINES.name(), true);
    actionsInView.put(ActionW.INVERSE_STACK.cmd(), false);
    actionsInView.put(ActionW.FILTERED_SERIES.cmd(), null);

    actionsInView.put(ActionW.ROTATION.cmd(), 0);
    actionsInView.put(ActionW.FLIP.cmd(), false);
  }

  @Override
  public void registerDefaultListeners() {
    addFocusListener(this);
    ToolTipManager.sharedInstance().registerComponent(this);
    renderingLayer.addLayerChangeListener(this);
    setTransferHandler(new DicomSeriesHandler(this));
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            Double currentZoom = (Double) actionsInView.get(ActionW.ZOOM.cmd());
            /*
             * Negative value means a default value according to the zoom type (pixel size, best fit...). Set again
             * to default value to compute again the position. For instance, the image cannot be center aligned
             * until the view has been repaint once (because the size is null).
             */
            if (currentZoom <= 0.0) {
              zoom(0.0);
            }
            repaint();
          }
        });
  }

  /**
   * Subscribes to {@code "mpr.crosshair"} events on the given texture and unsubscribes from the
   * previous one. Pass {@code null} to only unsubscribe.
   */
  private void subscribeToCrossHairEvents(DicomVolTexture newTexture) {
    if (mprCrossHairListener != null && mprCrossHairListenerSource != null) {
      mprCrossHairListenerSource.removePropertyChangeListener(mprCrossHairListener);
      mprCrossHairListenerSource = null;
    }
    if (newTexture == null) {
      mprCrossHairPosition = null;
      mprCrossHairRotation = null;
      mprCrossHairListener = null;
      return;
    }
    mprCrossHairListener =
        evt -> {
          if (ActionVol.MPR_CROSSHAIR.cmd().equals(evt.getPropertyName())
              && evt.getNewValue() instanceof Object[] arr
              && arr.length >= 2
              && arr[0] instanceof Vector3d pos
              && arr[1] instanceof Quaterniond rot) {
            mprCrossHairPosition = new Vector3d(pos);
            mprCrossHairRotation = new Quaterniond(rot);
            repaint();
          }
        };
    mprCrossHairListenerSource = newTexture;
    newTexture.addPropertyChangeListener(mprCrossHairListener);
  }

  @Override
  public void disposeView() {
    disableMouseAndKeyListener();
    subscribeToCrossHairEvents(null);
    removeFocusListener(this);
    ToolTipManager.sharedInstance().unregisterComponent(this);
    renderingLayer.removeLayerChangeListener(this);
    if (volTexture != null) {
      GuiUtils.getUICore().closeSeries(volTexture.getSeries());
    }
    GL2ES2 gl = OpenglUtils.getGL();
    if (gl != null) {
      program.destroy(gl);
      quadProgram.destroy(gl);
      texture.destroy(gl);
      SegVolumeTexture svt = segVolumeTexture;
      if (svt != null) {
        // destroy() releases the SegVolumeTexture's retain on the SegmentationVolume.
        // If no other consumer (e.g. MprController) is still holding the volume the CPU
        // buffers are freed inside release(); otherwise they stay alive for the MPR overlay.
        svt.destroy(gl);
        segVolumeTexture = null;
      }
      keptMaskTexture.destroy(gl);
    }
    stopMovie();
    cancelPicks();
    super.disposeView();
  }

  @Override
  public void handleLayerChanged(RenderingLayer<DicomImageElement> layer) {
    display();
  }

  public RenderingLayer<DicomImageElement> getRenderingLayer() {
    return renderingLayer;
  }

  public CrosshairCutMode getCrossHairCutMode() {
    CrosshairCutMode mode = mprCrossHairCutMode;
    return mode != null ? mode : CrosshairCutMode.NONE;
  }

  public Camera getCamera() {
    return camera;
  }

  public double getZoom() {
    return camera.getZoomFactor();
  }

  public void setVolTexture(DicomVolTexture volTexture) {
    if (this.volTexture != null) {
      this.volTexture.unregisterCrossHairRelay();
    }
    if (this.volTexture != volTexture) {
      // The cuts and the annotations belong to the volume they were made on.
      keptMask = null;
      keptMaskTexture.invalidate();
      editHistory.clear();
      cropBeforeAdjust = null;
      seedMarker = null;
      windowBeforePreview = null;
      sharedField = null;
      sharedFieldKey = null;
      cancelPicks();
      lasso.clear();
      annotations.clear();
      lengthStart = null;
      arrowStartTex = null;
      snapshots.clear();
      snapshotIndex = -1;
    }
    this.volTexture = volTexture;
    subscribeToCrossHairEvents(volTexture);
    if (volTexture != null && mprCrossHairCutMode != CrosshairCutMode.NONE) {
      volTexture.registerCrossHairRelay();
      syncMprPosition();
    }
    if (volTexture != null) {
      camera.setZoomFactor(-getBestFitViewScale());
      renderingLayer.setEnableRepaint(false);

      int quality = getDefaultQuality();
      renderingLayer.setQuality(quality);
      eventManager
          .getAction(ActionVol.VOL_QUALITY)
          .ifPresent(a -> a.setSliderValue(quality, false));
      renderingLayer.setEnableRepaint(true);
      // The anatomy volume always uses the modality's default preset.
      setVolumePreset(defaultPresetFor(volTexture.getModality()));
      // (Re)build the segmentation texture for this volume.
      updateSegmentation();
      maybeAutoCropToBone();
    } else {
      display();
    }
  }

  /** The preset a volume opens with: the bone preset for a CT, the modality's default otherwise. */
  public static Preset defaultPresetFor(org.weasis.dicom.codec.display.Modality modality) {
    if (modality == org.weasis.dicom.codec.display.Modality.CT) {
      return Preset.findPreset(CT_DEFAULT_PRESET, modality)
          .orElseGet(() -> Preset.getDefaultPreset(modality));
    }
    return Preset.getDefaultPreset(modality);
  }

  protected int getDefaultQuality() {
    if (volTexture == null) {
      return 0;
    }
    double val = Math.max(volTexture.getDepth(), volTexture.getMaxDimensionLength());
    return Math.min(RenderingLayer.MAX_QUALITY, (int) Math.round(val * camera.getFocalLength()));
  }

  @Override
  protected void paintComponent(Graphics graphs) {
    profiler.beginFrame();
    long panelStart = profiler.start();
    // Triggers display() — the GL pass — then the GLJPanel read-back into the Java2D pipeline.
    super.paintComponent(graphs);
    long overlayStart = profiler.start();
    if (graphs instanceof Graphics2D graphics2D) {
      draw(graphics2D);
    }
    profiler.endFrame(
        panelStart, overlayStart, camera.isAdjusting(), getPassWidth(), getPassHeight());
  }

  protected void draw(Graphics2D g2d) {
    Stroke oldStroke = g2d.getStroke();
    Paint oldColor = g2d.getPaint();

    Font defaultFont = getFont();
    g2d.setFont(defaultFont);

    Point2D p = getClipViewCoordinatesOffset();
    g2d.translate(p.getX(), p.getY());
    drawLayers(g2d, affineTransform, inverseTransform);
    g2d.translate(-p.getX(), -p.getY());

    drawPointer(g2d, pointerType);
    if (infoLayer != null && volTexture != null) {
      g2d.setFont(getLayerFont());
      infoLayer.paint(g2d);
    }
    drawOnTop(g2d);

    g2d.setFont(defaultFont);
    g2d.setPaint(oldColor);
    g2d.setStroke(oldStroke);
  }

  protected void drawOnTop(Graphics2D g2d) {
    if (infoLayer != null
        && volTexture != null
        && LangUtil.nullToFalse(infoLayer.getVisible())
        && infoLayer.getDisplayPreferences(LayerItem.IMAGE_ORIENTATION)) {
      drawLpsOrientation(g2d);
    }
    drawProgressBar(g2d, progressBar);
    drawSegLoadingMessage(g2d);
    drawTools(g2d);
  }

  /** The lasso, the lengths and arrows, the arrow being dragged and the status of a cut. */
  private void drawTools(Graphics2D g2d) {
    if (volTexture == null) {
      return;
    }
    lasso.draw(g2d);
    drawSeedMarker(g2d);
    Font font = getLayerFont();
    annotations.draw(g2d, this::projectTexCoord, font);
    if (lengthStart != null) {
      Point2D p = projectTexCoord(lengthStart);
      if (p != null) {
        Annotations3d.drawEndMark(g2d, p);
      }
    }
    if (arrowStartTex != null && arrowDragPoint != null) {
      Point2D a = projectTexCoord(arrowStartTex);
      if (a != null) {
        g2d.setFont(font);
        Annotations3d.drawItem(
            g2d, new Item(Kind.ARROW, arrowStartTex, arrowStartTex, ""), a, arrowDragPoint);
      }
    }
    String msg = statusMessage;
    if (msg != null) {
      g2d.setFont(font);
      FontMetrics fm = g2d.getFontMetrics();
      float x = (getWidth() - fm.stringWidth(msg)) / 2f;
      float y = getHeight() - fm.getHeight() * 3.5f;
      FontTools.paintColorFontOutline(g2d, msg, x, y, Color.YELLOW);
    }
  }

  /**
   * Paints the pending segmentation build at the bottom of the view, as the 2D and MPR views do.
   */
  private void drawSegLoadingMessage(Graphics2D g2d) {
    if (!segBuildRunning) {
      return;
    }
    String msg = org.weasis.dicom.viewer2d.Messages.getString("seg.loading");
    g2d.setFont(getLayerFont());
    FontMetrics fm = g2d.getFontMetrics();
    float x = (getWidth() - fm.stringWidth(msg)) / 2f;
    float y = getHeight() - fm.getHeight() * 2f;
    FontTools.paintColorFontOutline(g2d, msg, x, y, Color.ORANGE);
  }

  private void drawLpsOrientation(Graphics2D g2d) {
    int axisLength = GuiUtils.getScaleLength(30);

    // LPS unit directions in volume-texture space:
    //   X+ = Left  (R/L, blue),  Y+ = Superior (S/I, green),  -Z = Posterior (A/P, red)
    Vector3d lrDir = new Vector3d(1, 0, 0);
    Vector3d apDir = new Vector3d(0, 0, -1);
    Vector3d siDir = new Vector3d(0, 1, 0);

    // Build the combined model × view matrix (direction-only — translation is irrelevant).
    // Camera.currentModelMatrix = rotateX(90°) × translate(-0.5,-0.5,-0.5)
    Matrix4d mv = camera.getViewMatrix().mul(Camera.currentModelMatrix, new Matrix4d());
    mv.transformDirection(lrDir);
    mv.transformDirection(apDir);
    mv.transformDirection(siDir);

    // Camera space uses y-up; screen uses y-down — flip y.
    lrDir.y = -lrDir.y;
    apDir.y = -apDir.y;
    siDir.y = -siDir.y;

    // Scale to the desired pixel length.
    lrDir.mul(axisLength);
    apDir.mul(axisLength);
    siDir.mul(axisLength);

    Point2D topLeft = infoLayer.getPosition(LayerAnnotation.Position.TopLeft);
    Point origin =
        AxisDirection.computeOrigin(axisLength, new Vector3d[] {lrDir, apDir, siDir}, topLeft);

    Stroke savedStroke = g2d.getStroke();
    g2d.setStroke(new BasicStroke(2));

    g2d.setColor(PatientOrientation.blue); // R/L
    AxisDirection.drawAxisLine(g2d, lrDir, origin);

    g2d.setColor(PatientOrientation.red); // A/P
    AxisDirection.drawAxisLine(g2d, apDir, origin);

    g2d.setColor(PatientOrientation.green); // S/I
    AxisDirection.drawAxisLine(g2d, siDir, origin);

    g2d.setStroke(savedStroke);
  }

  @Override
  public void setProgressBar(JProgressBar bar) {
    this.progressBar = bar;
  }

  @Override
  public JProgressBar getProgressBar() {
    return progressBar;
  }

  @Override
  public void init(GLAutoDrawable glAutoDrawable) {
    GL2ES2 gl = glAutoDrawable.getGL().getGL2ES2();
    initShaders(gl);
  }

  public void initShaders(GL2ES2 gl) {
    WProperties preferences = GuiUtils.getUICore().getSystemPreferences();
    Color lightColor = preferences.getColorProperty(RenderingLayer.P_LIGHT_COLOR, Color.WHITE);
    Vector3f lColor =
        new Vector3f(
            lightColor.getRed() / 255f, lightColor.getGreen() / 255f, lightColor.getBlue() / 255f);
    Color bckColor =
        preferences.getColorProperty(RenderingLayer.P_BCK_COLOR, RenderingLayer.DEFAULT_BCK_COLOR);
    Vector3f bColor =
        new Vector3f(
            bckColor.getRed() / 255f, bckColor.getGreen() / 255f, bckColor.getBlue() / 255f);
    gl.glClearColor(bColor.x, bColor.y, bColor.z, 1);
    program.init(gl);
    program.allocateUniform(
        gl,
        "viewMatrix",
        (g, loc) ->
            g.glUniformMatrix4fv(
                loc,
                1,
                false,
                camera.getViewMatrix().invert().get(Buffers.newDirectFloatBuffer(16))));
    program.allocateUniform(
        gl,
        "projectionMatrix",
        (g, loc) ->
            g.glUniformMatrix4fv(
                loc,
                1,
                false,
                camera.getProjectionMatrix().invert().get(Buffers.newDirectFloatBuffer(16))));
    program.allocateUniform(
        gl,
        "depthSampleNumber",
        (g, loc) -> g.glUniform1i(loc, renderingLayer.getDepthSampleNumber()));
    program.allocateUniform(
        gl,
        "lutShape",
        (g, loc) ->
            ((GL2ES3) g).glUniform1ui(loc, isSegMode() ? 0 : renderingLayer.getLutShapeId()));

    program.allocateUniform(
        gl,
        "backgroundColor",
        (g, loc) -> g.glUniform3fv(loc, 1, bColor.get(Buffers.newDirectFloatBuffer(3))));

    for (int i = 0; i < 4; ++i) {
      int val = i;
      program.allocateUniform(
          gl,
          String.format("lights[%d].position", val), // NON-NLS
          (g, loc) ->
              g.glUniform4fv(loc, 1, camera.getLightOrigin().get(Buffers.newDirectFloatBuffer(4))));
      program.allocateUniform(
          gl,
          String.format("lights[%d].specularPower", val), // NON-NLS
          (g, loc) -> g.glUniform1f(loc, renderingLayer.getShadingOptions().getSpecularPower()));
      program.allocateUniform(
          gl,
          String.format("lights[%d].enabled", val), // NON-NLS
          (g, loc) -> g.glUniform1i(loc, val < 1 ? 1 : 0));
    }
    program.allocateUniform(
        gl,
        "lightColor",
        (g, loc) -> g.glUniform3fv(loc, 1, lColor.get(Buffers.newDirectFloatBuffer(3))));
    program.allocateUniform(
        gl, "shading", (g, loc) -> g.glUniform1i(loc, renderingLayer.isShading() ? 1 : 0));
    program.allocateUniform(
        gl, "smoothShading", (g, loc) -> g.glUniform1i(loc, isVolumeSmooth() ? 1 : 0)); // NON-NLS
    program.allocateUniform(
        gl,
        "accurateBlending",
        (g, loc) -> g.glUniform1i(loc, isVolumeSmooth() ? 1 : 0)); // NON-NLS
    program.allocateUniform(
        gl,
        "texelSize",
        (g, loc) -> {
          DicomVolTexture tex = volTexture;
          Vector3f texelSizeVal =
              tex != null
                  ? tex.getNormalizedTexelSize().get(new Vector3f())
                  : new Vector3f(1f, 1f, 1f);
          g.glUniform3fv(loc, 1, texelSizeVal.get(Buffers.newDirectFloatBuffer(3)));
        });

    program.allocateUniform(
        gl,
        "renderingType",
        (g, loc) -> ((GL2ES3) g).glUniform1ui(loc, renderingLayer.getRenderingType().getId()));
    program.allocateUniform(
        gl,
        "mipType",
        (g, loc) ->
            ((GL2ES3) g).glUniform1ui(loc, renderingLayer.getRenderingType().getMipTypeId()));
    program.allocateUniform(gl, "volTexture", (g, loc) -> g.glUniform1i(loc, 0));
    program.allocateUniform(gl, "colorMap", (g, loc) -> g.glUniform1i(loc, 1));
    program.allocateUniform(
        gl,
        "textureDataType",
        (g, loc) -> ((GL2ES3) g).glUniform1ui(loc, TextureData.getDataType(getPixelFormat())));

    program.allocateUniform(
        gl, "opacityFactor", (g, loc) -> g.glUniform1f(loc, (float) renderingLayer.getOpacity()));

    program.allocateUniform(
        gl,
        "inputLevelMin",
        (g, loc) -> {
          DicomVolTexture tex = volTexture;
          g.glUniform1f(loc, (tex == null || isSegMode()) ? 0 : (float) tex.getLevelMin());
        });
    program.allocateUniform(
        gl,
        "inputLevelMax",
        (g, loc) -> {
          DicomVolTexture tex = volTexture;
          g.glUniform1f(
              loc,
              isSegMode()
                  ? volumePreset.getWidth()
                  : (tex != null ? (float) tex.getLevelMax() : 1f));
        });
    program.allocateUniform(gl, "outputLevelMin", (g, loc) -> g.glUniform1f(loc, 0));
    program.allocateUniform(
        gl, "outputLevelMax", (g, loc) -> g.glUniform1f(loc, volumePreset.getWidth()));
    program.allocateUniform(
        gl,
        "windowWidth",
        (g, loc) ->
            g.glUniform1f(
                loc,
                isSegMode()
                    ? volumePreset.getColorMax() - volumePreset.getColorMin()
                    : renderingLayer.getWindowWidth()));
    program.allocateUniform(
        gl,
        "windowCenter",
        (g, loc) ->
            g.glUniform1f(
                loc,
                isSegMode()
                    ? (volumePreset.getColorMin() + volumePreset.getColorMax()) / 2f
                    : renderingLayer.getWindowCenter()));

    if (!useComputeShader) {
      // The FBO shader uses voxelUniforms410.glsl which has no default for ditherRay
      // (default uniform initializers require GLSL 4.2+).
      program.allocateUniform(gl, "ditherRay", (g, loc) -> g.glUniform1i(loc, 1));
      // Explicitly bind all three samplers to their texture units.
      // layout(binding=N) on samplers requires GLSL 4.2 and is not available in 4.1,
      // so we must set the units from Java.
      program.allocateUniform(gl, "volTexture", (g, loc) -> g.glUniform1i(loc, 0));
      program.allocateUniform(gl, "colorMap", (g, loc) -> g.glUniform1i(loc, 1));
      program.allocateUniform(gl, "lightingMap", (g, loc) -> g.glUniform1i(loc, 2));
      program.allocateUniform(
          gl, "keptTexture", (g, loc) -> g.glUniform1i(loc, KeptMaskTexture.UNIT)); // NON-NLS
      // The crosshair overlay derives the viewport pixel resolution from screen-space
      // derivatives of quadCoordinates in volumeFbo.frag, which is robust to the macOS GLJPanel
      // quirk that lets gl_FragCoord range over physical pixels even when the FBO color
      // attachment is sized at logical resolution — so no viewportSize uniform is needed here.
      // Only the size of the pixel-metric overlays has to be corrected when the adaptive pass
      // runs below the screen resolution.
      program.allocateUniform(
          gl, "overlayScale", (g, loc) -> g.glUniform1f(loc, getRenderScaleX()));
    }

    final IntBuffer intBuffer = IntBuffer.allocate(1);
    texture.init(gl);
    if (volTexture != null) {
      volTexture.init(gl);
    }
    if (volumePreset != null) {
      volumePreset.init(gl, renderingLayer.isInvertLut());
    }

    // MPR crosshair uniforms — position in normalized [0,1]³ volume-texture space
    program.allocateUniform(
        gl,
        "crosshairPos", // NON-NLS
        (g, loc) -> {
          Vector3d pos = mprCrossHairPosition;
          if (pos != null) {
            g.glUniform3f(loc, (float) pos.x, (float) pos.y, (float) pos.z);
          } else {
            g.glUniform3f(loc, 0.5f, 0.5f, 0.5f);
          }
        });
    program.allocateUniform(
        gl,
        "crosshairRot", // NON-NLS
        (g, loc) -> {
          Quaterniond rot = mprCrossHairRotation;
          Matrix3d m = (rot != null) ? new Matrix3d().set(rot) : new Matrix3d().identity();
          float[] f = {
            (float) m.m00, (float) m.m10, (float) m.m20,
            (float) m.m01, (float) m.m11, (float) m.m21,
            (float) m.m02, (float) m.m12, (float) m.m22
          };
          g.glUniformMatrix3fv(loc, 1, false, f, 0);
        });
    program.allocateUniform(
        gl,
        "crosshairVisible", // NON-NLS
        (g, loc) -> g.glUniform1i(loc, mprCrossHairPosition != null ? 1 : 0));
    program.allocateUniform(
        gl,
        "crosshairCutMode", // NON-NLS
        (g, loc) -> {
          CrosshairCutMode mode = mprCrossHairCutMode;
          g.glUniform1i(loc, mode != null ? mode.getId() : 0);
        });

    // ---- Segmentation overlay uniforms ----
    program.allocateUniform(
        gl,
        "segOverlayEnabled", // NON-NLS
        (g, loc) -> g.glUniform1i(loc, isSegTextureActive() ? 1 : 0));
    program.allocateUniform(
        gl, "segOnly", (g, loc) -> g.glUniform1i(loc, isSegOnly() ? 1 : 0)); // NON-NLS
    program.allocateUniform(
        gl, "segMaskMode", (g, loc) -> g.glUniform1i(loc, getSegMaskMode())); // NON-NLS
    program.allocateUniform(
        gl,
        "segSegmentCount", // NON-NLS
        (g, loc) -> {
          SegVolumeTexture svt = segVolumeTexture;
          g.glUniform1i(
              loc, svt != null && svt.isReady() ? svt.getSegVolume().getSegmentCount() : 0);
        });
    program.allocateUniform(
        gl, "segTexture", (g, loc) -> g.glUniform1i(loc, SegVolumeTexture.SEG_TEXTURE_UNIT));
    program.allocateUniform(
        gl, "segColorMap", (g, loc) -> g.glUniform1i(loc, SegVolumeTexture.SEG_COLOR_UNIT));
    program.allocateUniform(
        gl,
        "segOccTexture", // NON-NLS
        (g, loc) -> g.glUniform1i(loc, SegVolumeTexture.SEG_OCCUPANCY_UNIT));
    program.allocateUniform(
        gl, "segSmooth", (g, loc) -> g.glUniform1i(loc, isSegSmoothReady() ? 1 : 0)); // NON-NLS

    // Crop box in normalised volume-texture space, the whole volume when the crop is off
    program.allocateUniform(
        gl,
        "cropMin", // NON-NLS
        (g, loc) -> {
          CropBox box = getEffectiveCropBox();
          g.glUniform3f(loc, (float) box.minX(), (float) box.minY(), (float) box.minZ());
        });
    program.allocateUniform(
        gl,
        "cropMax", // NON-NLS
        (g, loc) -> {
          CropBox box = getEffectiveCropBox();
          g.glUniform3f(loc, (float) box.maxX(), (float) box.maxY(), (float) box.maxZ());
        });
    // Kept mask of the scalpel and keep/remove cuts; off until a cut was made
    program.allocateUniform(
        gl, "keptEnabled", (g, loc) -> g.glUniform1i(loc, isKeptMaskActive() ? 1 : 0)); // NON-NLS

    quadProgram.init(gl);
    // Blit uniforms: the compute path writes its image to unit 0 and always fills it entirely,
    // the FBO path blits from unit 3 and may have ray-cast only a sub-rectangle.
    quadProgram.allocateUniform(
        gl,
        "compute",
        (g, loc) ->
            g.glUniform1i(loc, useComputeShader ? 0 : FboRenderTexture.OUTPUT_TEXTURE_UNIT));
    quadProgram.allocateUniform(
        gl, "texScale", (g, loc) -> g.glUniform2f(loc, getRenderScaleX(), getRenderScaleY()));

    gl.glGenBuffers(1, intBuffer);
    vertexBuffer = intBuffer.get(0);
    gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexBuffer);
    gl.glBufferData(
        GL.GL_ARRAY_BUFFER,
        (long) vertexBufferData.length * Float.BYTES,
        Buffers.newDirectFloatBuffer(vertexBufferData),
        GL.GL_STATIC_DRAW);
  }

  /** Smooth rendering of the volume, see DicomVolTexture#isSmoothing(). */
  private boolean isVolumeSmooth() {
    DicomVolTexture tex = volTexture;
    return tex != null && tex.isSmoothing() && !isSegMode();
  }

  private boolean isSegMode() {
    return volumePreset != null && "Segmentation".equals(volumePreset.getName()); // NON-NLS
  }

  private PixelFormat getPixelFormat() {
    DicomVolTexture tex = volTexture;
    if (tex == null) {
      return PixelFormat.UNSIGNED_SHORT;
    }
    PixelFormat format = tex.getPixelFormat();
    if (isSegMode()) {
      if (format == PixelFormat.SIGNED_SHORT) {
        return PixelFormat.UNSIGNED_SHORT;
      }
    }
    return format;
  }

  public void display(GLAutoDrawable drawable) {
    render(drawable.getGL().getGL2ES2());
  }

  /**
   * Fraction of the render target that this frame is ray-cast into — below 1 only on the adaptive
   * FBO path while the camera is being dragged. The compute path always fills its image.
   */
  private float getRenderScaleX() {
    return texture instanceof FboRenderTexture fbo ? fbo.getRenderScaleX() : 1f;
  }

  private float getRenderScaleY() {
    return texture instanceof FboRenderTexture fbo ? fbo.getRenderScaleY() : 1f;
  }

  /** Resolution the volume is actually ray-cast at this frame, for the profiling report. */
  private int getPassWidth() {
    return Math.round(getRenderScaleX() * texture.getWidth());
  }

  private int getPassHeight() {
    return Math.round(getRenderScaleY() * texture.getHeight());
  }

  /**
   * Binds the segmentation textures on units 4 and 5, falling back to 1×1×1 placeholders when no
   * segmentation is loaded. Drivers validate every sampler of the active program, even those the
   * shader never reaches, so leaving the {@code usampler3D} unit empty makes strict implementations
   * (macOS) report an incomplete texture on each draw.
   */
  private void bindSegTextures(GL2ES2 gl) {
    SegVolumeTexture svt = segVolumeTexture;
    if (svt != null && svt.isReady()) {
      svt.bind(gl);
      if (!svt.bindOccupancy(gl)) {
        bindOccupancyPlaceholder(gl);
      }
      return;
    }
    bindOccupancyPlaceholder(gl);
    if (segPlaceholderTextureId <= 0) {
      IntBuffer buf = IntBuffer.allocate(2);
      gl.glGenTextures(2, buf);
      segPlaceholderTextureId = buf.get(0);
      segPlaceholderColorId = buf.get(1);

      gl.glActiveTexture(GL.GL_TEXTURE0 + SegVolumeTexture.SEG_TEXTURE_UNIT);
      gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segPlaceholderTextureId);
      gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
      gl.glTexImage3D(
          GL2ES2.GL_TEXTURE_3D,
          0,
          GL2ES3.GL_R8UI,
          1,
          1,
          1,
          0,
          GL2GL3.GL_RED_INTEGER,
          GL.GL_UNSIGNED_BYTE,
          Buffers.newDirectByteBuffer(1));

      gl.glActiveTexture(GL.GL_TEXTURE0 + SegVolumeTexture.SEG_COLOR_UNIT);
      gl.glBindTexture(GL.GL_TEXTURE_2D, segPlaceholderColorId);
      gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
      gl.glTexImage2D(
          GL.GL_TEXTURE_2D,
          0,
          GL.GL_RGBA8,
          1,
          1,
          0,
          GL.GL_RGBA,
          GL.GL_UNSIGNED_BYTE,
          Buffers.newDirectByteBuffer(4));
    } else {
      gl.glActiveTexture(GL.GL_TEXTURE0 + SegVolumeTexture.SEG_TEXTURE_UNIT);
      gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segPlaceholderTextureId);
      gl.glActiveTexture(GL.GL_TEXTURE0 + SegVolumeTexture.SEG_COLOR_UNIT);
      gl.glBindTexture(GL.GL_TEXTURE_2D, segPlaceholderColorId);
    }
    gl.glActiveTexture(GL.GL_TEXTURE0);
  }

  /** Binds an empty 1×1×1 occupancy on its unit, for the same reason as in bindSegTextures. */
  private void bindOccupancyPlaceholder(GL2ES2 gl) {
    gl.glActiveTexture(GL.GL_TEXTURE0 + SegVolumeTexture.SEG_OCCUPANCY_UNIT);
    if (segOccupancyPlaceholderId <= 0) {
      int[] ids = new int[1];
      gl.glGenTextures(1, ids, 0);
      segOccupancyPlaceholderId = ids[0];
      gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segOccupancyPlaceholderId);
      gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL2ES2.GL_TEXTURE_3D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
      gl.glTexImage3D(
          GL2ES2.GL_TEXTURE_3D,
          0,
          GL.GL_R8,
          1,
          1,
          1,
          0,
          GL2ES2.GL_RED,
          GL.GL_UNSIGNED_BYTE,
          Buffers.newDirectByteBuffer(4));
    } else {
      gl.glBindTexture(GL2ES2.GL_TEXTURE_3D, segOccupancyPlaceholderId);
    }
    gl.glActiveTexture(GL.GL_TEXTURE0);
  }

  private void render(GL2ES2 gl2) {
    long start = profiler.start();
    gl2.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
    if (volTexture != null && volTexture.isReadyForDisplay()) {
      int sampleCount = renderingLayer.getQuality();
      if (camera.isAdjusting()) {
        double quality =
            GuiUtils.getUICore()
                    .getLocalPersistence()
                    .getIntProperty(
                        RenderingLayer.P_DYNAMIC_QUALITY,
                        RenderingLayer.DEFAULT_DYNAMIC_QUALITY_RATE)
                / 100.0;
        sampleCount = Math.max(64, (int) Math.round(sampleCount * quality));
      }
      renderingLayer.setDepthSampleNumber(sampleCount);

      if (useComputeShader) {
        // --- Compute shader path (OpenGL >= 4.3) ---
        // Cast to GL4 here: compute shaders (glDispatchCompute, glBindImageTexture) are GL4-only.
        GL4 gl4 = gl2.getGL4();
        program.use(gl4);
        program.setUniforms(gl4);
        volTexture.render(gl4);
        if (volumePreset != null) {
          volumePreset.render(gl4, renderingLayer.isInvertLut());
        }
        // Bind segmentation overlay textures (units 4 and 5)
        bindSegTextures(gl4);
        keptMaskTexture.bind(gl4, isKeptMaskActive() ? keptMask : null);
        texture.render(gl4);
        quadProgram.use(gl4);
        quadProgram.setUniforms(gl4);

        gl4.glEnable(GL.GL_BLEND);
        gl4.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        gl4.glEnableVertexAttribArray(0);
        gl4.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexBuffer);
        gl4.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0);
        gl4.glActiveTexture(GL.GL_TEXTURE0);
        gl4.glBindTexture(GL.GL_TEXTURE_2D, texture.getId());
        gl4.glDrawArrays(GL.GL_TRIANGLES, 0, vertexBufferData.length / 2);
        gl4.glDisableVertexAttribArray(0);
        gl4.glDisable(GL.GL_BLEND);
      } else {
        // --- FBO fragment-shader fallback path (OpenGL 3.3+, e.g., macOS GL3) ---
        program.use(gl2);
        program.setUniforms(gl2);

        // Bind volume and LUT textures on their expected texture units
        volTexture.render(gl2);
        if (volumePreset != null) {
          volumePreset.render(gl2, renderingLayer.isInvertLut());
        }
        // Bind segmentation overlay textures (units 4 and 5)
        bindSegTextures(gl2);
        keptMaskTexture.bind(gl2, isKeptMaskActive() ? keptMask : null);

        // Set up the vertex array so FboRenderTexture.render() can call glDrawArrays
        gl2.glEnableVertexAttribArray(0);
        gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexBuffer);
        gl2.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0);

        // Render into FBO (binds FBO, draws quad, unbinds FBO, restores viewport)
        texture.render(gl2);
        gl2.glDisableVertexAttribArray(0);

        // Step 2: Blit the FBO colour-attachment texture to the screen using the quad program.
        // The FBO output lives on unit 3 (FboRenderTexture.OUTPUT_TEXTURE_UNIT), so the quad
        // sampler never disturbs the 3D volume texture on unit 0, and texScale restricts the
        // sampling to the sub-rectangle that was ray-cast.
        quadProgram.use(gl2);
        quadProgram.setUniforms(gl2);

        gl2.glEnable(GL.GL_BLEND);
        gl2.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

        gl2.glEnableVertexAttribArray(0);
        gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, vertexBuffer);
        gl2.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0);
        gl2.glActiveTexture(GL.GL_TEXTURE0 + FboRenderTexture.OUTPUT_TEXTURE_UNIT);
        gl2.glBindTexture(GL.GL_TEXTURE_2D, texture.getId());
        gl2.glDrawArrays(GL.GL_TRIANGLES, 0, vertexBufferData.length / 2);
        gl2.glDisableVertexAttribArray(0);
        gl2.glDisable(GL.GL_BLEND);
      }
    }
    profiler.endGpu(gl2, start);
  }

  public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
    GL2ES2 gl2 = drawable.getGL().getGL2ES2();
    gl2.glViewport(0, 0, width, height);
    camera.resetTransformation();
  }

  public void dispose(GLAutoDrawable drawable) {
    // GL4 gl2 = drawable.getGL().getGL4();
    // FIXME destroy when release of cache
    //    if (volTexture != null) {
    //      volTexture.destroy(gl2);
    //    }
  }

  public void updateSegmentation() {
    Type currentType = getSegType();
    DicomVolTexture tex = volTexture;
    Volume<?, ?> volume = tex == null ? null : tex.getVolume();
    // Only the SEG files actually shown are resampled. Each one costs a full copy of the displayed
    // volume's grid (one byte per image voxel, hundreds of MB for a thin-slice CT), and a study may
    // carry dozens; building the hidden ones exhausts the heap for overlays nobody asked for.
    // Ticking one back on is caught by hasUnrenderedVisibleSeg() and rebuilds the texture.
    List<SpecialElementRegion> segList =
        currentType.requiresSegTexture() && volume != null
            ? tex.getSegmentations().stream().filter(SpecialElementRegion::isVisible).toList()
            : null;
    if (segList == null || segList.isEmpty()) {
      segBuildGeneration.incrementAndGet(); // invalidate any in-flight build
      setSegBuildRunning(false);
      destroySegTexture();
      display();
      return;
    }

    if (!segBuildRunning && isSegTextureUpToDate(segList)) {
      // The same SEG files are already uploaded: switching between the display modes only changes
      // shader uniforms, so render the existing texture instead of rebuilding it.
      display();
      return;
    }

    // The heavy work (mask decoding, splatting, resampling, GPU upload) runs asynchronously so
    // the EDT never freezes on large SEG objects; a message is shown in the view meanwhile.
    int generation = segBuildGeneration.incrementAndGet();
    setSegBuildRunning(true);
    final Volume<?, ?> imageVolume = volume;
    CompletableFuture.runAsync(() -> buildSegTexture(generation, segList, imageVolume))
        .exceptionally(
            e -> {
              LOGGER.error("Building segmentation texture", e);
              GuiExecutor.execute(
                  () -> {
                    if (generation == segBuildGeneration.get()) {
                      setSegBuildRunning(false);
                      destroySegTexture();
                      display();
                    }
                  });
              return null;
            });
  }

  /**
   * Background part of {@link #updateSegmentation()}: builds one image-aligned volume per SEG file,
   * combines them when needed, uploads the GPU texture through the shared GL context, then installs
   * the result on the EDT. Aborts silently when a newer build supersedes this one.
   */
  private void buildSegTexture(
      int generation, List<SpecialElementRegion> segList, Volume<?, ?> imageVolume) {
    List<RenderedSeg> rendered = new ArrayList<>();
    SegmentationVolume segVolume =
        buildCombinedSegVolume(generation, segList, imageVolume, rendered);

    // An interrupt cuts the build short, and a partial texture would be missing some SEG files.
    if (Thread.currentThread().isInterrupted() || generation != segBuildGeneration.get()) {
      return; // superseded by a newer request
    }
    if (segVolume == null) {
      GuiExecutor.execute(
          () -> {
            if (generation == segBuildGeneration.get()) {
              setSegBuildRunning(false);
              destroySegTexture();
              display();
            }
          });
      return;
    }

    SegVolumeTexture newSvt = new SegVolumeTexture(segVolume, new Vector3d(1.0, 1.0, 1.0));
    // Upload from this worker thread through the shared GL context
    newSvt.uploadVolumeDataAsync();

    GuiExecutor.execute(() -> installSegTexture(generation, newSvt, rendered));
  }

  /**
   * Resamples the SEG files onto the displayed volume's grid and combines them into one volume,
   * recording each file's segment-number offset in {@code rendered}. Returns {@code null} when no
   * file yielded anything usable.
   *
   * <p>Each resampled volume is a full copy of the image grid, so they are merged and freed as they
   * complete rather than collected first: the peak is the merge target plus the handful of builds
   * in flight, not one volume per SEG file. A lone file needs no merge and keeps its cached volume,
   * which the MPR overlays can then reuse.
   */
  private SegmentationVolume buildCombinedSegVolume(
      int generation,
      List<SpecialElementRegion> segList,
      Volume<?, ?> imageVolume,
      List<RenderedSeg> rendered) {

    if (segList.size() == 1) {
      AlignedSeg single = buildAlignedSeg(generation, segList.getFirst(), imageVolume);
      if (single == null) {
        return null;
      }
      rendered.add(new RenderedSeg(single.seg(), 0));
      return single.volume();
    }

    // Offsets follow segList order rather than completion order, so a study always maps a given
    // file to the same segment numbers however the concurrent builds interleave. Files that end up
    // yielding nothing simply leave their range unused.
    Map<Integer, RegionAttributes> combined = new HashMap<>();
    List<Integer> indexes = new ArrayList<>(segList.size());
    int[] offsets = new int[segList.size()];
    int offset = 0;
    for (int i = 0; i < segList.size(); i++) {
      indexes.add(i);
      offsets[i] = offset;
      int maxSegNum = 0;
      for (Map.Entry<Integer, ? extends RegionAttributes> e :
          segList.get(i).getSegAttributes().entrySet()) {
        combined.put(offset + e.getKey(), e.getValue());
        maxSegNum = Math.max(maxSegNum, e.getKey());
      }
      offset += maxSegNum;
    }

    SegVolumeMerger merger = new SegVolumeMerger(combined);
    long start = System.nanoTime();
    // mapBounded returns the non-null results in input order, so rendered stays in segList order.
    rendered.addAll(
        SegBuildScheduler.mapBounded(
            indexes,
            i -> {
              AlignedSeg as = buildAlignedSeg(generation, segList.get(i), imageVolume);
              if (as == null) {
                return null;
              }
              try {
                merger.merge(as.volume(), offsets[i]);
              } finally {
                // Freed as soon as it is merged. MPR keeps a retain() on the volumes it displays,
                // so this releases only the copies this build owns.
                as.seg().disposeAlignedVolume(imageVolume);
              }
              return new RenderedSeg(as.seg(), offsets[i]);
            }));
    LOGGER.debug(
        "Merged {} segmentation volumes ({} stamped voxels) in {} ms",
        rendered.size(),
        merger.stamped(),
        (System.nanoTime() - start) / 1_000_000);
    return merger.merged();
  }

  /**
   * Accumulates image-aligned SEG volumes into a single volume as they are built. Segment numbers
   * of each file are shifted by that file's offset so files sharing segment numbers do not
   * overwrite each other.
   */
  private static final class SegVolumeMerger {

    private final Map<Integer, RegionAttributes> combined;
    private SegmentationVolume merged;
    private long stamped;

    SegVolumeMerger(Map<Integer, RegionAttributes> combined) {
      this.combined = combined;
    }

    /** The merge target is created from the first volume, which fixes the grid for the rest. */
    synchronized void merge(SegmentationVolume volume, int offset) {
      if (merged == null) {
        merged = volume.createCompatible(combined);
      }
      stamped += volume.mergeInto(merged, segNum -> segNum + offset);
    }

    synchronized SegmentationVolume merged() {
      return merged;
    }

    synchronized long stamped() {
      return stamped;
    }
  }

  /**
   * Builds (or reuses) the image-aligned volume of a single SEG file. Returns {@code null} when the
   * file yields nothing usable or when a newer segmentation request has superseded this build.
   */
  private AlignedSeg buildAlignedSeg(
      int generation, SpecialElementRegion seg, Volume<?, ?> imageVolume) {
    if (generation != segBuildGeneration.get() || !(seg instanceof SegSpecialElement segElement)) {
      return null;
    }
    DicomSeries segSeries = segElement.getMediaReader().getMediaSeries();
    if (segSeries == null) {
      return null;
    }
    // Reuse the SEG's per-image-volume cached resample when available so we do not redo the
    // expensive frame splatting if MPR (or another 3D view) already built it for the same volume.
    SegmentationVolume segVol =
        segElement.getOrBuildAlignedVolume(
            imageVolume, s -> SegVolumeBuilder.build(s, segSeries, imageVolume));
    if (segVol == null) {
      return null;
    }
    if (segVol.isEmpty()) {
      // Empty resample is useless to keep cached: drop it from the SEG so a future request can
      // retry (e.g. once the image volume finishes loading).
      segElement.disposeAlignedVolume(imageVolume);
      return null;
    }
    return new AlignedSeg(segElement, segVol);
  }

  /** EDT part of the asynchronous build: swaps the new texture in and refreshes the view. */
  private void installSegTexture(
      int generation, SegVolumeTexture newSvt, List<RenderedSeg> rendered) {
    if (generation != segBuildGeneration.get()) {
      // Superseded while uploading — discard the freshly-built texture.
      GL2ES2 gl = OpenglUtils.getGL();
      if (gl != null) {
        newSvt.destroy(gl);
      }
      return;
    }
    this.renderedSegs = rendered;
    // Apply the tree's visibility/opacity state to the colour LUT. This runs on the EDT (no
    // current GL context), so stash the LUT — SegVolumeTexture.bind() uploads it on the GL
    // thread at the next render. buildSegmentColorLUT() is pure CPU work.
    newSvt.setPendingColorLut(newSvt.getSegVolume().buildSegmentColorLUT(buildRegionOverrideMap()));
    newSvt.setOccupancyListener(this::display);
    newSvt.setSmoothSurfaces(segSmooth);

    SegVolumeTexture old = this.segVolumeTexture;
    this.segVolumeTexture = newSvt;
    if (old != null) {
      GL2ES2 gl = OpenglUtils.getGL();
      if (gl != null) {
        old.destroy(gl);
      }
      // Do NOT free the underlying SegmentationVolume here: it is now owned by the
      // SegSpecialElement's per-image-volume cache (see SegSpecialElement.alignedVolumes) and
      // may still be in use by the MPR overlay or by a re-upload of this very texture.
    }
    setSegBuildRunning(false);
    display();
  }

  private void setSegBuildRunning(boolean running) {
    this.segBuildRunning = running;
    repaint();
  }

  /**
   * {@code true} when the current texture already holds exactly the SEG files of {@code segList}.
   */
  private boolean isSegTextureUpToDate(List<SpecialElementRegion> segList) {
    SegVolumeTexture svt = segVolumeTexture;
    if (svt == null) {
      return false;
    }
    List<RenderedSeg> rendered = renderedSegs;
    if (rendered.size() != segList.size()) {
      return false;
    }
    for (int i = 0; i < rendered.size(); i++) {
      if (rendered.get(i).seg() != segList.get(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Refreshes only the segment colour LUT on the GPU using the current visibility / opacity state
   * from the UI tree ({@link Preset#getRegionMap()}). This is much cheaper than rebuilding the
   * entire segmentation texture and should be used when only segment visibility or opacity has
   * changed.
   */
  public void refreshSegColorLUT() {
    if (segBuildRunning) {
      // A texture build is in flight; it reads the tree state when it installs the texture.
      return;
    }
    SegVolumeTexture svt = segVolumeTexture;
    if (svt == null || hasUnrenderedVisibleSeg()) {
      // No texture yet, or the user enabled a SEG file that is not part of the current texture
      // (e.g. its volume could not be built when the texture was created): rebuild it fully.
      // updateSegmentation() is a no-op when no segmentation display mode is active.
      updateSegmentation();
      return;
    }
    // Build the colour LUT from the Preset region map (reflects the tree's checkbox state)
    Map<Integer, RegionAttributes> overrideAttrs = buildRegionOverrideMap();
    svt.setPendingColorLut(svt.getSegVolume().buildSegmentColorLUT(overrideAttrs));
    display();
  }

  /** Returns {@code true} when a visible SEG file is missing from the rendered texture. */
  private boolean hasUnrenderedVisibleSeg() {
    DicomVolTexture tex = volTexture;
    if (tex == null) {
      return false;
    }
    List<RenderedSeg> rendered = renderedSegs;
    for (SpecialElementRegion seg : tex.getSegmentations()) {
      if (seg.isVisible()
          && seg instanceof SegSpecialElement sse
          && rendered.stream().noneMatch(rs -> rs.seg() == sse)) {
        return true;
      }
    }
    return false;
  }

  /** A SEG file together with its volume resampled onto the displayed image volume's grid. */
  private record AlignedSeg(SegSpecialElement seg, SegmentationVolume volume) {}

  /** A SEG file contained in the current texture and its segment-number offset. */
  private record RenderedSeg(SegSpecialElement seg, int offset) {}

  /**
   * Builds a flat segment-number → RegionAttributes map suitable for {@link
   * SegmentationVolume#buildSegmentColorLUT(Map)}. Region-level state comes from the Preset's
   * region map (the tool tree's copies, keyed by {@code "<regionUID>::<groupKey>"}), shifted by the
   * per-file offset used when the rendered volume was merged. On top of that, the file-level
   * checkbox is authoritative: every region of an unchecked SEG file is forced invisible, even when
   * the tree copies are unavailable or stale.
   */
  private Map<Integer, RegionAttributes> buildRegionOverrideMap() {
    List<RenderedSeg> rendered = renderedSegs;
    Map<Integer, RegionAttributes> result = new HashMap<>();

    Map<String, List<SegRegion<?>>> regionMap = Preset.getRegionMap();
    if (regionMap != null && !regionMap.isEmpty()) {
      for (Map.Entry<String, List<SegRegion<?>>> entry : regionMap.entrySet()) {
        Integer offset = resolveSegOffset(rendered, entry.getKey());
        if (offset == null) {
          continue; // this segmentation is not part of the rendered texture
        }
        for (SegRegion<?> region : entry.getValue()) {
          result.put(region.getId() + offset, region);
        }
      }
    }

    for (RenderedSeg rs : rendered) {
      if (!rs.seg().isVisible()) {
        for (Map.Entry<Integer, ? extends RegionAttributes> e :
            rs.seg().getSegAttributes().entrySet()) {
          if (e.getValue() instanceof SegRegion<?> region) {
            SegRegion<?> hidden = region.copy();
            hidden.setVisible(false);
            result.put(e.getKey() + rs.offset(), hidden);
          }
        }
      }
    }
    return result;
  }

  /**
   * Resolves the segment-number offset of the segmentation owning the given Preset region-map key
   * ({@code "<regionUID>::<groupKey>"}), or {@code null} when that segmentation is not rendered.
   */
  private static Integer resolveSegOffset(List<RenderedSeg> rendered, String key) {
    if (rendered.isEmpty()) {
      return 0;
    }
    for (RenderedSeg rs : rendered) {
      if (key.startsWith(rs.seg().getRegionUID() + "::")) { // NON-NLS
        return rs.offset();
      }
    }
    return null;
  }

  /** Returns this view's segmentation display mode (never {@code null}). */
  public Type getSegType() {
    return segType;
  }

  /** Applies a new segmentation display mode to this view and rebuilds/destroys its texture. */
  public void setSegType(Type type) {
    if (type != null && type != segType) {
      this.segType = type;
      updateSegmentation();
    }
  }

  private boolean isSegOnly() {
    return getSegType() == Type.SEG_ONLY;
  }

  /**
   * Returns true when a segmentation texture is ready and the current mode renders it — overlay,
   * segmentation-only, or one of the voxel mask modes.
   */
  private boolean isSegTextureActive() {
    SegVolumeTexture svt = segVolumeTexture;
    return svt != null && svt.isReady() && segType.requiresSegTexture();
  }

  /**
   * Returns the mask mode passed to the shader: {@code 0} = none, {@code 1} = include (only the
   * real voxels inside visible segments are rendered), {@code 2} = exclude (those voxels are
   * removed from the rendering).
   */
  private int getSegMaskMode() {
    if (segType == Type.SEG_MASK_INCLUDE) {
      return 1;
    }
    return segType == Type.SEG_MASK_EXCLUDE ? 2 : 0;
  }

  public boolean isSegSmooth() {
    return segSmooth;
  }

  /** Renders the segments as smooth surfaces (trilinear occupancy) or as label voxels. */
  public void setSegSmooth(boolean smooth) {
    if (smooth != segSmooth) {
      this.segSmooth = smooth;
      SegVolumeTexture svt = segVolumeTexture;
      if (svt != null) {
        svt.setSmoothSurfaces(smooth);
      }
      display();
    }
  }

  /** {@code true} when the segments are rendered and their occupancy texture is up to date. */
  private boolean isSegSmoothReady() {
    SegVolumeTexture svt = segVolumeTexture;
    return svt != null && isSegTextureActive() && svt.isSmoothSurfaceReady();
  }

  public boolean isCropEnabled() {
    return cropEnabled;
  }

  public CropBox getCropBox() {
    return cropBox;
  }

  /**
   * Sets the crop box applied to the volume rendering.
   *
   * @param adjusting {@code true} while a slider is dragged, to render at the interactive quality
   */
  public void setCropBox(boolean enabled, CropBox box, boolean adjusting) {
    Objects.requireNonNull(box);
    EditHistory.CropState before =
        cropBeforeAdjust != null
            ? cropBeforeAdjust
            : new EditHistory.CropState(cropEnabled, cropBox);
    EditHistory.CropState after = new EditHistory.CropState(enabled, box);
    if (adjusting) {
      cropBeforeAdjust = before;
    } else {
      cropBeforeAdjust = null;
      if (!before.equals(after)) {
        editHistory.push(EditHistory.crop(before, after, this::applyCropState));
      }
    }
    applyCrop(enabled, box, adjusting);
    if (!adjusting) {
      notifyToolState();
    }
  }

  /** The crop box state now, for the history and the panels. */
  public EditHistory.CropState getCropState() {
    return new EditHistory.CropState(cropEnabled, cropBox);
  }

  private void applyCropState(EditHistory.CropState state) {
    applyCrop(state.enabled(), state.box(), false);
    notifyCropListeners();
  }

  private void applyCrop(boolean enabled, CropBox box, boolean adjusting) {
    this.cropEnabled = enabled;
    this.cropBox = box;
    camera.setAdjusting(adjusting);
    updatePivot();
    display();
  }

  /**
   * Tells the crop panel and the tool bar that the crop box changed outside of the panel (undo,
   * redo, auto-crop): they listen to the mouse tool changes of the event manager.
   */
  private void notifyCropListeners() {
    notifyToolState();
  }

  private CropBox getEffectiveCropBox() {
    return cropEnabled ? cropBox : CropBox.FULL;
  }

  /** Rotation and zoom centre on the crop box when it is on, on the volume otherwise. */
  private void updatePivot() {
    VolumeSpace space = volumeSpace();
    if (space == null) {
      return;
    }
    CropBox box = getEffectiveCropBox();
    Vector3d centre =
        space.worldOfCrop(
            (box.minX() + box.maxX()) / 2.0,
            (box.minY() + box.maxY()) / 2.0,
            (box.minZ() + box.maxZ()) / 2.0,
            new Vector3d());
    camera.setPivot(centre);
  }

  /** The zoom at which the crop box fills the view, or the best fit when the crop is off. */
  private double fitZoomForVisible() {
    VolumeSpace space = volumeSpace();
    if (space == null || !cropEnabled || cropBox.isFull()) {
      return -getBestFitViewScale();
    }
    return org.weasis.dicom.viewer3d.geometry.CameraFraming.fitZoom(
        space.worldOfCrop(cropBox.minX(), cropBox.minY(), cropBox.minZ(), new Vector3d()),
        space.worldOfCrop(cropBox.maxX(), cropBox.maxY(), cropBox.maxZ(), new Vector3d()),
        camera.getRotation(),
        camera.getFieldOfView(),
        getAspectRatio());
  }

  /** Centres the view on the visible part of the volume and zooms so that it fills the view. */
  public void fitToVisible() {
    if (volTexture == null) {
      return;
    }
    camera.resetPan();
    if (cropEnabled && !cropBox.isFull()) {
      camera.setZoomFactor(fitZoomForVisible());
    } else {
      zoom(0.0);
    }
    eventManager
        .getAction(ActionW.ZOOM)
        .ifPresent(a -> a.setRealValue(camera.getZoomFactor(), false));
  }

  // ---- Crop fitted to the bone (Addis Ortho) ----

  /** {@code true} while the bone is being searched for the crop. */
  private volatile boolean autoCropRunning;

  /** Whether {@link #autoCropToBone} can run: a fully loaded CT and no search running. */
  public boolean canAutoCropToBone() {
    DicomVolTexture tex = volTexture;
    return tex != null
        && tex.getModality() == org.weasis.dicom.codec.display.Modality.CT
        && tex.isFullyLoaded()
        && !autoCropRunning;
  }

  /** Fits the crop box to the bone, as an undoable step; Fit to bone in the crop panel. */
  public void autoCropToBone() {
    startAutoCrop(true);
  }

  /** Once per CT, when it is fully loaded, if the preference to open on the bone is on. */
  private void maybeAutoCropToBone() {
    DicomVolTexture tex = volTexture;
    if (tex == null || tex == autoCroppedTexture || !canAutoCropToBone()) {
      return;
    }
    boolean onBone =
        GuiUtils.getUICore()
            .getLocalPersistence()
            .getBooleanProperty(
                RenderingLayer.P_OPEN_CT_ON_BONE, RenderingLayer.DEFAULT_OPEN_CT_ON_BONE);
    if (!onBone) {
      return;
    }
    autoCroppedTexture = tex;
    startAutoCrop(false);
  }

  private void startAutoCrop(boolean asked) {
    DicomVolTexture tex = volTexture;
    Volume<?, ?> volume = tex == null ? null : tex.getVolume();
    if (!canAutoCropToBone() || volume == null) {
      return;
    }
    Vector3i texture = new Vector3i(tex.getWidth(), tex.getHeight(), tex.getDepth());
    Vector3d spacing = volume.getPixelRatio();
    Vector3i size = volume.getSize();
    Vector3d texelMm =
        new Vector3d(
            spacing.x * size.x / texture.x,
            spacing.y * size.y / texture.y,
            spacing.z * size.z / texture.z);
    VoxelSampler sampler = voxelSampler();
    autoCropRunning = true;
    notifyToolState();
    CUT_EXECUTOR.execute(
        () -> {
          Optional<CropBox> box = Optional.empty();
          try {
            box =
                BoneExtent.find(
                    sampler,
                    texture,
                    texelMm,
                    BoneExtent.DEFAULT_THRESHOLD_HU,
                    BoneExtent.DEFAULT_MARGIN_MM,
                    () -> volTexture != tex);
          } catch (Throwable e) { // NOSONAR the view must not stay locked
            LOGGER.error("Crop to the bone", e);
          } finally {
            Optional<CropBox> found = box;
            GuiExecutor.execute(() -> finishAutoCrop(tex, found, asked));
          }
        });
  }

  private void finishAutoCrop(DicomVolTexture tex, Optional<CropBox> box, boolean asked) {
    autoCropRunning = false;
    try {
      if (volTexture != tex) {
        return;
      }
      if (box.isPresent()) {
        setCropBox(true, box.get(), false);
        fitToVisible();
        showStatus(Messages.getString("crop.auto.done"));
      } else if (asked) {
        showStatus(Messages.getString("crop.auto.none"));
      }
    } finally {
      notifyToolState();
    }
  }

  // ---- Seed marker and threshold preview of the Bones panel (Addis Ortho) ----

  /** Texture coordinate of the region grow seed, drawn as a target, or {@code null}. */
  private volatile Vector3d seedMarker;

  /** The window and level before the threshold preview, or {@code null} without a preview. */
  private int[] windowBeforePreview;

  /** Shows the region grow seed at a patient position (LPS, mm); {@code null} hides it. */
  public void setSeedMarker(Vector3d lps) {
    Vector3d marker = null;
    DicomVolTexture tex = volTexture;
    VolumeSpace space = volumeSpace();
    if (lps != null && tex != null && space != null) {
      try {
        Vector3d v = tex.getVolume().lpsToVoxel(lps, new Vector3d());
        Vector3d t = space.texCoordOfVolume(v.x, v.y, v.z, new Vector3d());
        if (t.x >= 0 && t.x <= 1 && t.y >= 0 && t.y <= 1 && t.z >= 0 && t.z <= 1) {
          marker = t;
        }
      } catch (UnsupportedOperationException e) {
        // a volume without geometry has no patient position
      }
    }
    seedMarker = marker;
    repaint();
  }

  /**
   * Previews a bone threshold: the window is narrowed to {@code hu}, so what is at or above it is
   * shown opaque in the preset's colours and the rest is clear; {@code null} puts the window back.
   * The window/level tool or a preset change ends the preview as well.
   */
  public void previewThreshold(Double hu) {
    if (hu == null) {
      int[] before = windowBeforePreview;
      windowBeforePreview = null;
      if (before != null) {
        renderingLayer.setWindowWidth(before[0]);
        renderingLayer.setWindowCenter(before[1], true);
      }
      return;
    }
    if (windowBeforePreview == null) {
      windowBeforePreview =
          new int[] {renderingLayer.getWindowWidth(), renderingLayer.getWindowCenter()};
    }
    renderingLayer.setWindowWidth(2);
    renderingLayer.setWindowCenter((int) Math.round(hu) + 1, true);
  }

  public boolean isPreviewingThreshold() {
    return windowBeforePreview != null;
  }

  private void drawSeedMarker(Graphics2D g2d) {
    Vector3d t = seedMarker;
    Point2D p = t == null ? null : projectTexCoord(t);
    if (p == null) {
      return;
    }
    Stroke stroke = g2d.getStroke();
    double r = 9;
    g2d.setStroke(new BasicStroke(3f));
    g2d.setColor(Color.BLACK);
    g2d.draw(new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r));
    g2d.setStroke(new BasicStroke(1.5f));
    g2d.setColor(Color.YELLOW);
    g2d.draw(new Ellipse2D.Double(p.getX() - r, p.getY() - r, 2 * r, 2 * r));
    g2d.draw(new Line2D.Double(p.getX() - r - 4, p.getY(), p.getX() - 3, p.getY()));
    g2d.draw(new Line2D.Double(p.getX() + 3, p.getY(), p.getX() + r + 4, p.getY()));
    g2d.draw(new Line2D.Double(p.getX(), p.getY() - r - 4, p.getX(), p.getY() - 3));
    g2d.draw(new Line2D.Double(p.getX(), p.getY() + 3, p.getX(), p.getY() + r + 4));
    g2d.setStroke(stroke);
  }

  // ---- Kept mask, scalpel and keep/remove (Addis Ortho) ----

  /** The cuts made on this volume, or {@code null} when none was made. */
  public KeptMask getKeptMask() {
    return keptMask;
  }

  private boolean isKeptMaskActive() {
    KeptMask mask = keptMask;
    return mask != null && !mask.isFull();
  }

  /** The coordinate systems of the displayed volume, or {@code null} without a volume. */
  public VolumeSpace volumeSpace() {
    DicomVolTexture tex = volTexture;
    if (tex == null || tex.getVolume() == null) {
      return null;
    }
    KeptMask mask = keptMask;
    Vector3i texture = new Vector3i(tex.getWidth(), tex.getHeight(), tex.getDepth());
    Vector3i grid = mask != null ? mask.space().grid() : KeptMask.gridFor(texture);
    return new VolumeSpace(grid, texture, tex.getVolume().getSize(), tex.getNormalizedTexelSize());
  }

  private KeptMask getOrCreateKeptMask() {
    KeptMask mask = keptMask;
    if (mask == null) {
      VolumeSpace space = volumeSpace();
      if (space == null) {
        return null;
      }
      mask = new KeptMask(space);
      keptMask = mask;
      LOGGER.info(
          "Kept mask {}x{}x{} ({} MB on the CPU, {} MB on the GPU)",
          space.grid().x,
          space.grid().y,
          space.grid().z,
          mask.bytes() / (1024 * 1024),
          space.gridVoxels() / (1024 * 1024));
    }
    return mask;
  }

  /** The renderer's opacity of a voxel value: preset, window/level and LUT shape. */
  public TransferFunction transferFunction() {
    return new TransferFunction(
        volumePreset.getColors(),
        renderingLayer.getWindowWidth(),
        renderingLayer.getWindowCenter(),
        renderingLayer.getOpacity(),
        isSegMode() ? TransferFunction.LINEAR : renderingLayer.getLutShapeId());
  }

  /**
   * The field the renderer samples, texture voxel by texture voxel: the volume as uploaded, scaled
   * and smoothed as the texture is. A new instance per operation; its slices are made on demand.
   */
  public synchronized VoxelSampler voxelSampler() {
    DicomVolTexture tex = volTexture;
    Volume<?, ?> volume = tex == null ? null : tex.getVolume();
    if (volume == null) {
      return (x, y, z) -> Double.NaN;
    }
    FieldKey key =
        new FieldKey(
            tex,
            isUploadSmoothed(tex),
            new Vector3i(tex.getWidth(), tex.getHeight(), tex.getDepth()));
    if (sharedField == null || !key.equals(sharedFieldKey)) {
      Vector3d texel = tex.getTexelSize();
      VolumeSmoother smoother =
          TextureField.smootherOf(key.smoothed(), key.size().x, key.size().y, texel.x, texel.z);
      sharedField = new TextureField(new VolumeSliceSource(volume), key.size(), smoother);
      sharedFieldKey = key;
    }
    return sharedField;
  }

  /** What the shared texture field was built for; a change means a new field. */
  private record FieldKey(DicomVolTexture texture, boolean smoothed, Vector3i size) {}

  /** Whether VolumeBuilder smooths this texture: a smoothed, single-channel, 16-bit volume. */
  private static boolean isUploadSmoothed(DicomVolTexture tex) {
    int cvType = tex.getVolume().getCvType();
    int depth = org.opencv.core.CvType.depth(cvType);
    return tex.isSmoothing()
        && org.opencv.core.CvType.channels(cvType) == 1
        && (depth == org.opencv.core.CvType.CV_16S || depth == org.opencv.core.CvType.CV_16U);
  }

  /** The axial slices of the CPU volume as the upload reads them, one at a time. */
  private static final class VolumeSliceSource implements TextureField.SliceSource {
    private final Volume<?, ?> volume;
    private final int cvDepth;
    private final int channels;

    VolumeSliceSource(Volume<?, ?> volume) {
      this.volume = volume;
      this.cvDepth = org.opencv.core.CvType.depth(volume.getCvType());
      this.channels = org.opencv.core.CvType.channels(volume.getCvType());
    }

    @Override
    public int width() {
      return volume.getSizeX();
    }

    @Override
    public int height() {
      return volume.getSizeY();
    }

    @Override
    public int depth() {
      return volume.getSizeZ();
    }

    @Override
    public int unsignedOffset() {
      return cvDepth == org.opencv.core.CvType.CV_16S ? 32768 : 0;
    }

    @Override
    public float[] slice(int z) {
      int n = width() * height();
      float[] out = new float[n];
      if (channels != 1) {
        // A colour volume is windowed on its luminance in the shaders.
        for (int y = 0; y < height(); y++) {
          for (int x = 0; x < width(); x++) {
            out[y * width() + x] = (float) volume.getNearestDouble(x, y, z, 0);
          }
        }
        return out;
      }
      PlanarImage image = volume.getAxialSlice(z);
      try {
        switch (cvDepth) {
          case org.opencv.core.CvType.CV_16S, org.opencv.core.CvType.CV_16U -> {
            short[] data = new short[n];
            image.get(0, 0, data);
            boolean signed = cvDepth == org.opencv.core.CvType.CV_16S;
            for (int i = 0; i < n; i++) {
              out[i] = signed ? data[i] : data[i] & 0xFFFF;
            }
          }
          case org.opencv.core.CvType.CV_8U, org.opencv.core.CvType.CV_8S -> {
            byte[] data = new byte[n];
            image.get(0, 0, data);
            boolean signed = cvDepth == org.opencv.core.CvType.CV_8S;
            for (int i = 0; i < n; i++) {
              out[i] = signed ? data[i] : data[i] & 0xFF;
            }
          }
          case org.opencv.core.CvType.CV_32F -> image.get(0, 0, out);
          default -> {
            for (int y = 0; y < height(); y++) {
              for (int x = 0; x < width(); x++) {
                out[y * width() + x] = (float) volume.getNearestDouble(x, y, z, 0);
              }
            }
          }
        }
      } finally {
        image.release();
      }
      return out;
    }
  }

  /** The segmentation's part in the rendering, as the shaders' uniforms get it. */
  private VolumePicker.SegMode segMode() {
    if (!isSegTextureActive()) {
      return VolumePicker.SegMode.NONE;
    }
    return switch (segType) {
      case SEG_ONLY -> VolumePicker.SegMode.SEG_ONLY;
      case SEG_OVERLAY -> VolumePicker.SegMode.OVERLAY;
      case SEG_MASK_INCLUDE -> VolumePicker.SegMode.MASK_INCLUDE;
      case SEG_MASK_EXCLUDE -> VolumePicker.SegMode.MASK_EXCLUDE;
      default -> VolumePicker.SegMode.NONE;
    };
  }

  /** The visible segments as rendered; one instance per operation. */
  private SegmentField segmentField() {
    SegVolumeTexture svt = segVolumeTexture;
    if (svt == null || !isSegTextureActive() || svt.getSegVolume().isDisposed()) {
      return SegmentField.NONE;
    }
    // The smooth surfaces are rendered only once their occupancy matches this visibility, which
    // is exactly when the CPU field must read the occupancy too.
    return new SegVolumeField(svt.getSegVolume(), svt.getColorLut(), isSegSmoothReady());
  }

  /**
   * Why a point of the volume cannot be picked now, or {@code null} when it can: the rendering
   * shows a segmentation that is still being built, so the CPU cannot tell what is on screen.
   */
  public String pickUnavailableReason() {
    if (!segType.requiresSegTexture()) {
      return null;
    }
    SegVolumeTexture svt = segVolumeTexture;
    if (segBuildRunning || svt == null || !svt.isReady() || svt.getSegVolume().isDisposed()) {
      return Messages.getString("pick.unavailable.loading");
    }
    return null;
  }

  /**
   * Why keep only this and remove this cannot act now, or {@code null}: as {@link
   * #pickUnavailableReason()}, and the segmentation-only mode, which shows no anatomy to cut.
   */
  public String keepRemoveUnavailableReason() {
    String reason = pickUnavailableReason();
    if (reason != null) {
      return reason;
    }
    return isSegOnly() ? Messages.getString("pick.unavailable.segonly") : null;
  }

  /** Whether a texture coordinate is removed by the cuts or the MPR crosshair cut. */
  private boolean isCutAway(Vector3d t) {
    KeptMask mask = keptMask;
    if (mask != null && !mask.isKeptAt(t)) {
      return true;
    }
    return isCrosshairCut(t);
  }

  /** The {@code isCrosshairCut} of vrFunctions.glsl. */
  private boolean isCrosshairCut(Vector3d t) {
    CrosshairCutMode mode = getCrossHairCutMode();
    Vector3d pos = mprCrossHairPosition;
    if (mode == CrosshairCutMode.NONE || pos == null) {
      return false;
    }
    Quaterniond rot = mprCrossHairRotation;
    Vector3d v = new Vector3d(t).sub(pos);
    if (rot != null) {
      new Matrix3d().set(rot).transform(v);
    }
    return switch (mode.getId()) {
      case 1 -> v.x < 0;
      case 2 -> v.x > 0;
      case 3 -> v.y < 0;
      case 4 -> v.y > 0;
      case 5 -> v.z > 0;
      case 6 -> v.z < 0;
      case 7 -> v.x < 0 && v.z > 0;
      case 8 -> v.x > 0 && v.z > 0;
      case 9 -> v.x < 0 && v.z < 0;
      case 10 -> v.x > 0 && v.z < 0;
      case 11 -> v.x < 0 && v.z > 0 && v.y < 0;
      case 12 -> v.x > 0 && v.z > 0 && v.y < 0;
      case 13 -> v.x < 0 && v.z < 0 && v.y < 0;
      case 14 -> v.x > 0 && v.z < 0 && v.y < 0;
      case 15 -> v.x < 0 && v.z > 0 && v.y > 0;
      case 16 -> v.x > 0 && v.z > 0 && v.y > 0;
      case 17 -> v.x < 0 && v.z < 0 && v.y > 0;
      case 18 -> v.x > 0 && v.z < 0 && v.y > 0;
      default -> false;
    };
  }

  /**
   * Everything a pick reads, taken on the event dispatch thread when the pick is asked for, so the
   * worker never looks at a view that moved on; the key tells whether the view still matches when
   * the result comes back.
   */
  private record PickSnapshot(
      PickStateKey key,
      VolumeSpace space,
      VoxelSampler sampler,
      TransferFunction transfer,
      java.util.function.Predicate<Vector3d> cutAway,
      VolumePicker.SegMode segMode,
      SegmentField segments,
      Matrix4d viewInverse,
      Matrix4d projectionInverse,
      Vector3d cropMin,
      Vector3d cropMax,
      int width,
      int height) {}

  /** The state a pick depends on; a result for another state is stale. */
  private record PickStateKey(
      Matrix4d view,
      Matrix4d projection,
      int maskGeneration,
      Type segType,
      boolean segSmooth,
      boolean cropEnabled,
      CropBox cropBox,
      Preset preset,
      int windowWidth,
      int windowCenter,
      double opacity,
      int lutShape,
      CrosshairCutMode cutMode,
      Vector3d crosshairPos) {}

  private PickStateKey pickStateKey() {
    KeptMask mask = keptMask;
    Vector3d pos = mprCrossHairPosition;
    return new PickStateKey(
        camera.getViewMatrix(),
        camera.getProjectionMatrix(),
        mask == null ? -1 : mask.generation(),
        segType,
        isSegSmoothReady(),
        cropEnabled,
        cropBox,
        volumePreset,
        renderingLayer.getWindowWidth(),
        renderingLayer.getWindowCenter(),
        renderingLayer.getOpacity(),
        renderingLayer.getLutShapeId(),
        getCrossHairCutMode(),
        pos == null ? null : new Vector3d(pos));
  }

  /** The snapshot of the current view for a pick, or {@code null} when nothing can be picked. */
  private PickSnapshot pickSnapshot() {
    VolumeSpace space = volumeSpace();
    if (space == null || !hasValidContent() || getWidth() <= 0 || getHeight() <= 0) {
      return null;
    }
    if (pickUnavailableReason() != null) {
      return null;
    }
    CropBox box = getEffectiveCropBox();
    // The cut-away test reads the mask's bits, which only the event dispatch thread changes; the
    // crosshair cut is frozen in the key and read through the same method.
    return new PickSnapshot(
        pickStateKey(),
        space,
        voxelSampler(),
        transferFunction(),
        this::isCutAway,
        segMode(),
        segmentField(),
        camera.getViewMatrix().invert(),
        camera.getProjectionMatrix().invert(),
        new Vector3d(box.minX(), box.minY(), box.minZ()),
        new Vector3d(box.maxX(), box.maxY(), box.maxZ()),
        getWidth(),
        getHeight());
  }

  private static Optional<Hit> pick(PickSnapshot s, Point p, PickRequest request) {
    VolumePicker picker =
        new VolumePicker(
                s.space(), s.sampler(), s.transfer(), s.cutAway(), s.segMode(), s.segments())
            .withCancel(() -> request != null && request.cancelled);
    return picker.pick(
        s.viewInverse(),
        s.projectionInverse(),
        p.getX() * 2.0 / s.width() - 1.0,
        1.0 - 2.0 * p.getY() / s.height(),
        s.cropMin(),
        s.cropMax());
  }

  /**
   * The first visible point of the volume under a view point: the renderer's own ray marched on the
   * CPU until it is half opaque. Empty when the ray meets nothing. Blocks the caller; the tools use
   * {@link #pickAsync} instead.
   */
  public Optional<Hit> pick(Point p) {
    PickSnapshot snapshot = pickSnapshot();
    return snapshot == null ? Optional.empty() : pick(snapshot, p, null);
  }

  /** A pick asked for by a tool, run on the pick worker. */
  private static final class PickRequest {
    final PickSnapshot snapshot;
    final Point point;
    final java.util.function.Consumer<Optional<Hit>> onResult;
    volatile boolean cancelled;

    PickRequest(
        PickSnapshot snapshot, Point point, java.util.function.Consumer<Optional<Hit>> onResult) {
      this.snapshot = snapshot;
      this.point = point;
      this.onResult = onResult;
    }
  }

  /**
   * Picks under a view point on the pick worker and hands the result to {@code onResult} on the
   * event dispatch thread, unless the view changed meanwhile (the result is then dropped) or a
   * newer pick replaced this one before it started (a drag's requests coalesce).
   */
  public void pickAsync(Point p, java.util.function.Consumer<Optional<Hit>> onResult) {
    PickSnapshot snapshot = pickSnapshot();
    if (snapshot == null) {
      onResult.accept(Optional.empty());
      return;
    }
    PickRequest request = new PickRequest(snapshot, new Point(p), onResult);
    PickRequest replaced = queuedPick.getAndSet(request);
    if (replaced != null) {
      replaced.cancelled = true;
    }
    PickRequest running = runningPick;
    if (running != null) {
      running.cancelled = true;
    }
    PICK_EXECUTOR.execute(
        () -> {
          PickRequest mine = queuedPick.getAndSet(null);
          if (mine == null) {
            return; // taken by a later submission
          }
          runningPick = mine;
          Optional<Hit> hit;
          try {
            hit = mine.cancelled ? Optional.empty() : pick(mine.snapshot, mine.point, mine);
          } catch (RuntimeException e) {
            LOGGER.error("3D pick", e);
            hit = Optional.empty();
          } finally {
            runningPick = null;
          }
          Optional<Hit> result = hit;
          GuiExecutor.execute(
              () -> {
                if (mine.cancelled) {
                  return;
                }
                if (volTexture == null || !mine.snapshot.key().equals(pickStateKey())) {
                  LOGGER.debug("3D pick dropped: the view changed");
                  return;
                }
                mine.onResult.accept(result);
              });
        });
  }

  private void cancelPicks() {
    PickRequest queued = queuedPick.getAndSet(null);
    if (queued != null) {
      queued.cancelled = true;
    }
    PickRequest running = runningPick;
    if (running != null) {
      running.cancelled = true;
    }
  }

  /** Patient position (LPS, mm) of a picked point, when the volume knows its geometry. */
  public Optional<Vector3d> lpsOf(Hit hit) {
    Volume<?, ?> volume = volTexture == null ? null : volTexture.getVolume();
    if (volume == null) {
      return Optional.empty();
    }
    try {
      Vector3d v = hit.volume();
      return Optional.of(volume.voxelToLps(v.x, v.y, v.z, new Vector3d()));
    } catch (UnsupportedOperationException e) {
      return Optional.empty();
    }
  }

  /** View pixel of a texture coordinate, {@code null} behind the camera. */
  private Point2D projectTexCoord(Vector3d t) {
    VolumeSpace space = volumeSpace();
    if (space == null) {
      return null;
    }
    Vector3d w = space.worldOfTexCoord(t, new Vector3d());
    Vector4d c = camera.getViewProjectionMatrix().transform(new Vector4d(w, 1.0));
    if (c.w <= 1e-9) {
      return null;
    }
    return new Point2D.Double(
        (c.x / c.w + 1.0) / 2.0 * getWidth(), (1.0 - c.y / c.w) / 2.0 * getHeight());
  }

  /**
   * Texture coordinate of the point of a view pixel's ray at the given distance from the camera.
   */
  private Vector3d texCoordAtDepth(Point p, double depth) {
    VolumeSpace space = volumeSpace();
    if (space == null) {
      return null;
    }
    Matrix4d viewInverse = camera.getViewMatrix().invert();
    Vector4d o = viewInverse.transform(new Vector4d(0, 0, 0, 1));
    Vector4d d =
        camera
            .getProjectionMatrix()
            .invert()
            .transform(
                new Vector4d(
                    p.getX() * 2.0 / getWidth() - 1.0, 1.0 - 2.0 * p.getY() / getHeight(), 0, 1));
    Vector4d dir = viewInverse.transform(new Vector4d(d.x, d.y, d.z, 0));
    Vector3d world = new Vector3d(dir.x, dir.y, dir.z).normalize().mul(depth).add(o.x, o.y, o.z);
    return space.texCoordOfWorld(world, new Vector3d());
  }

  public Lasso getLasso() {
    return lasso;
  }

  public void lassoStart(Point p) {
    if (volTexture == null) {
      return;
    }
    lasso.start(p);
    repaint();
  }

  public void lassoExtend(Point p) {
    lasso.extend(p);
    repaint();
  }

  public void lassoClose() {
    lasso.close();
    repaint();
    notifyToolState();
  }

  public void lassoClear() {
    lasso.clear();
    repaint();
    notifyToolState();
  }

  /** Tells the tool bars that what the cut commands can do changed. */
  private void notifyToolState() {
    if (eventManager instanceof EventManager manager) {
      manager.fireMouseToolChanged();
    }
  }

  public boolean isCutRunning() {
    return cutRunning;
  }

  public boolean isLightSeparation() {
    return lightSeparation;
  }

  public void setLightSeparation(boolean separation) {
    this.lightSeparation = separation;
  }

  /** Cuts away the voxels inside or outside the lasso, through the whole depth. */
  public void cutLasso(LassoCut.Side side) {
    if (!lasso.isClosed() || cutRunning) {
      return;
    }
    KeptMask mask = getOrCreateKeptMask();
    if (mask == null) {
      return;
    }
    List<Point2D> polygon = List.copyOf(lasso.points());
    Matrix4d vp = camera.getViewProjectionMatrix();
    int w = getWidth();
    int h = getHeight();
    runCut(
        mask,
        Messages.getString("cut.busy"),
        () -> {
          LassoCut.Result r = LassoCut.compute(mask.space(), vp, w, h, polygon, side, mask.bits());
          LOGGER.info(
              "Scalpel cut {}: {} voxels in {} ms ({} rows visited, {} skipped)",
              side,
              r.diff().voxels(),
              r.millis(),
              r.rowsVisited(),
              r.rowsSkipped());
          return r.diff();
        },
        lasso::clear);
  }

  /** Keeps only the structure under the point; everything else is cut away. */
  public void keepOnlyAt(Point p) {
    growAndCut(p, true);
  }

  /** Cuts away the structure under the point. */
  public void removeAt(Point p) {
    growAndCut(p, false);
  }

  private void growAndCut(Point p, boolean keep) {
    if (cutRunning) {
      return;
    }
    String unavailable = keepRemoveUnavailableReason();
    if (unavailable != null) {
      showStatus(unavailable);
      return;
    }
    pickAsync(p, hit -> growAndCut(hit, keep));
  }

  private void growAndCut(Optional<Hit> hit, boolean keep) {
    if (cutRunning) {
      return;
    }
    KeptMask mask = hit.isPresent() ? getOrCreateKeptMask() : null;
    if (mask == null) {
      showStatus(Messages.getString("cut.nothing"));
      return;
    }
    VoxelFlood.Visibility visible = visibilityForSelection();
    VoxelSampler sampler = voxelSampler();
    Vector3i seed = hit.get().grid();
    int separation = lightSeparation ? SEPARATION_VOXELS : 0;
    runCut(
        mask,
        Messages.getString("cut.selecting"),
        () -> {
          Optional<VoxelFlood.Result> region =
              VoxelFlood.grow(mask.space(), mask.bits(), sampler, visible, seed, separation);
          if (region.isEmpty()) {
            return null;
          }
          VoxelFlood.Result r = region.get();
          LOGGER.info(
              "{} structure of {} voxels (separated {}) in {} ms",
              keep ? "Keep" : "Remove",
              r.voxels(),
              r.separated(),
              r.millis());
          return keep
              ? VoxelFlood.keepOnly(mask.bits(), r.region())
              : VoxelFlood.remove(mask.bits(), r.region());
        },
        () -> {});
  }

  /**
   * What the renderer shows of the anatomy, voxel by voxel: its opacity, and in the mask modes its
   * place inside or outside the visible segments.
   */
  private VoxelFlood.Visibility visibilityForSelection() {
    VoxelFlood.Visibility opaque =
        VoxelFlood.Visibility.ofOpacity(transferFunction(), VISIBLE_OPACITY);
    VolumePicker.SegMode mode = segMode();
    if (mode != VolumePicker.SegMode.MASK_INCLUDE && mode != VolumePicker.SegMode.MASK_EXCLUDE) {
      return opaque;
    }
    SegmentField segments = segmentField();
    VolumeSpace space = volumeSpace();
    return new VoxelFlood.Visibility() {
      @Override
      public boolean visible(int x, int y, int z, double value) {
        if (!opaque.visible(x, y, z, value)) {
          return false;
        }
        Vector3d t = space.texCoordOfTextureVoxel(x, y, z, new Vector3d());
        return !mode.masksAnatomy(segments.insideVisible(t));
      }

      @Override
      public long workingBytes() {
        return segments.workingBytes();
      }
    };
  }

  /** Computes a cut on the worker thread and applies it on the event dispatch thread. */
  private void runCut(KeptMask mask, String message, Supplier<MaskDiff> work, Runnable after) {
    cutRunning = true;
    statusMessage = message;
    setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
    repaint();
    CUT_EXECUTOR.execute(
        () -> {
          MaskDiff diff = null;
          String failure = null;
          try {
            diff = work.get();
          } catch (VoxelFlood.BudgetException e) {
            LOGGER.warn("3D cut refused: {}", e.getMessage());
            failure =
                String.format(
                    Messages.getString("cut.memory"),
                    e.neededBytes() >> 20,
                    e.availableBytes() >> 20);
          } catch (Throwable e) { // NOSONAR an OutOfMemoryError must not leave the view locked
            LOGGER.error("3D cut", e);
            failure = Messages.getString("cut.failed");
          } finally {
            MaskDiff result = diff;
            String why = failure;
            GuiExecutor.execute(() -> finishCut(mask, result, why, after));
          }
        });
  }

  /** EDT part of {@link #runCut}: always clears the running state, then applies the result. */
  private void finishCut(KeptMask mask, MaskDiff result, String failure, Runnable after) {
    cutRunning = false;
    statusMessage = null;
    setCursor(null);
    try {
      if (failure != null) {
        showStatus(failure);
      } else if (result != null && keptMask == mask) {
        mask.apply(result);
        if (!result.isEmpty()) {
          editHistory.push(EditHistory.cut(mask));
        }
        after.run();
        showStatus(
            result.isEmpty()
                ? Messages.getString("cut.nothing")
                : String.format(Messages.getString("cut.removed"), result.voxels()));
      } else if (keptMask == mask) {
        showStatus(Messages.getString("cut.nothing"));
      }
    } finally {
      cutChanged();
    }
  }

  private void cutChanged() {
    notifyToolState();
    display();
  }

  /** The crop and cut history of this view. */
  public EditHistory getEditHistory() {
    return editHistory;
  }

  /** Takes back the last cut or crop box change. */
  public boolean undoEdit() {
    if (cutRunning || !editHistory.undo()) {
      return false;
    }
    cutChanged();
    notifyCropListeners();
    return true;
  }

  /** Does again the last cut or crop box change taken back. */
  public boolean redoEdit() {
    if (cutRunning || !editHistory.redo()) {
      return false;
    }
    cutChanged();
    notifyCropListeners();
    return true;
  }

  /** {@code true} when neither a crop box nor a cut hides any part of the volume. */
  public boolean isWholeVolumeShown() {
    KeptMask mask = keptMask;
    return (!cropEnabled || cropBox.isFull()) && (mask == null || mask.isFull());
  }

  /**
   * Shows the whole volume again, with the crop box off and every cut restored, as one undoable
   * step, and clears the lasso.
   */
  public void restoreVolume() {
    KeptMask mask = keptMask;
    if (cutRunning) {
      return;
    }
    lasso.clear();
    EditHistory.Group group = new EditHistory.Group();
    if (mask != null && !mask.isFull()) {
      mask.restore();
      group.add(EditHistory.cut(mask));
    }
    EditHistory.CropState before = getCropState();
    EditHistory.CropState after = new EditHistory.CropState(false, CropBox.FULL);
    if (!before.equals(after)) {
      applyCrop(false, CropBox.FULL, false);
      group.add(EditHistory.crop(before, after, this::applyCropState));
    }
    if (!group.isEmpty()) {
      editHistory.push(group.build());
    }
    cutChanged();
    notifyCropListeners();
  }

  public void clearCutHistory() {
    KeptMask mask = keptMask;
    if (mask != null) {
      mask.clearHistory();
    }
    editHistory.clear();
    cutChanged();
  }

  private void showStatus(String message) {
    statusMessage = message;
    repaint();
    if (statusTimer != null) {
      statusTimer.stop();
    }
    statusTimer =
        new Timer(
            2000,
            _ -> {
              if (!cutRunning) {
                statusMessage = null;
                repaint();
              }
            });
    statusTimer.setRepeats(false);
    statusTimer.start();
  }

  // ---- Lengths and arrows ----

  public Annotations3d getAnnotations() {
    return annotations;
  }

  /** First click sets the first end of a length, the second one the other end. */
  public void lengthClick(Point p) {
    String unavailable = pickUnavailableReason();
    if (unavailable != null) {
      showStatus(unavailable);
      return;
    }
    pickAsync(p, this::lengthPicked);
  }

  private void lengthPicked(Optional<Hit> hit) {
    if (hit.isEmpty()) {
      showStatus(Messages.getString("cut.nothing"));
      return;
    }
    if (lengthStart == null) {
      lengthStart = hit.get().texCoord();
    } else {
      VolumeSpace space = volumeSpace();
      Volume<?, ?> volume = volTexture.getVolume();
      Vector3d a = space.volumeOfTexCoord(lengthStart, new Vector3d());
      double mm = Annotations3d.lengthMm(a, hit.get().volume(), volume.getPixelRatio());
      annotations.add(
          new Item(Kind.LENGTH, lengthStart, hit.get().texCoord(), String.format("%.1f mm", mm)));
      lengthStart = null;
    }
    repaint();
  }

  public void arrowStart(Point p) {
    String unavailable = pickUnavailableReason();
    if (unavailable != null) {
      showStatus(unavailable);
      arrowStartTex = null;
      return;
    }
    arrowDragPoint = p;
    pickAsync(
        p,
        hit -> {
          if (hit.isEmpty()) {
            arrowStartTex = null;
            arrowDragPoint = null;
            return;
          }
          arrowStartTex = hit.get().texCoord();
          arrowStartDepth = hit.get().depth();
          repaint();
        });
  }

  public void arrowDrag(Point p) {
    if (arrowStartTex != null) {
      arrowDragPoint = p;
      repaint();
    }
  }

  /** Ends the arrow on the surface under the point, or at the depth of its tail when it misses. */
  public void arrowEnd(Point p) {
    Vector3d tail = arrowStartTex;
    double tailDepth = arrowStartDepth;
    if (tail == null) {
      return;
    }
    arrowDragPoint = p;
    pickAsync(
        p,
        hit -> {
          if (arrowStartTex != tail) {
            return; // the arrow was cancelled or restarted meanwhile
          }
          Vector3d end = hit.map(Hit::texCoord).orElseGet(() -> texCoordAtDepth(p, tailDepth));
          if (end != null && !end.equals(tail)) {
            annotations.add(new Item(Kind.ARROW, tail, end, ""));
          }
          arrowStartTex = null;
          arrowDragPoint = null;
          repaint();
        });
  }

  /** Drops the first point of a length being measured, if any. */
  public boolean cancelPendingMeasure() {
    if (lengthStart == null && arrowStartTex == null) {
      return false;
    }
    lengthStart = null;
    arrowStartTex = null;
    arrowDragPoint = null;
    repaint();
    return true;
  }

  public void clearAnnotations() {
    annotations.clear();
    cancelPendingMeasure();
    repaint();
  }

  // ---- View state snapshots and the quick movie ----

  public void takeSnapshot() {
    if (volTexture == null) {
      return;
    }
    snapshots.add(ViewSnapshot.of(this));
    snapshotIndex = snapshots.size() - 1;
    showStatus(String.format(Messages.getString("view.snapshot.taken"), snapshotIndex + 1));
  }

  public int snapshotCount() {
    return snapshots.size();
  }

  /** Restores the previous ({@code -1}) or next ({@code +1}) snapshot. */
  public void showSnapshot(int step) {
    if (snapshots.isEmpty()) {
      return;
    }
    int index = Math.clamp(snapshotIndex < 0 ? 0 : snapshotIndex + step, 0, snapshots.size() - 1);
    snapshotIndex = index;
    snapshots.get(index).applyTo(this);
    showStatus(
        String.format(Messages.getString("view.snapshot.shown"), index + 1, snapshots.size()));
  }

  public boolean isMovieRunning() {
    return movieTimer != null;
  }

  /** Starts or stops the turn about the vertical axis of the screen. */
  public void toggleMovie() {
    if (movieTimer != null) {
      stopMovie();
      return;
    }
    movieTimer =
        new Timer(
            40,
            _ -> {
              if (volTexture == null) {
                stopMovie();
                return;
              }
              camera.rotateAboutView(new Vector3d(0, 1, 0), Math.toRadians(2));
            });
    movieTimer.start();
  }

  private void stopMovie() {
    if (movieTimer != null) {
      movieTimer.stop();
      movieTimer = null;
      camera.setAdjusting(false);
      repaint();
    }
  }

  /** Alt + left drag draws the lasso whatever the current mouse tool, as the scalpel key does. */
  private final class AltLassoHandler extends MouseAdapter {
    private boolean active;

    @Override
    public void mousePressed(MouseEvent e) {
      if (!e.isConsumed()
          && SwingUtilities.isLeftMouseButton(e)
          && MouseToolListener.isAltDown(e)
          && hasValidContent()) {
        active = true;
        lassoStart(e.getPoint());
        e.consume();
      }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
      if (active) {
        lassoExtend(e.getPoint());
        e.consume();
      }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
      if (active) {
        active = false;
        lassoClose();
        e.consume();
      }
    }
  }

  private void destroySegTexture() {
    renderedSegs = List.of();
    SegVolumeTexture svt = segVolumeTexture;
    if (svt != null) {
      segVolumeTexture = null;
      GL2ES2 gl = OpenglUtils.getGL();
      if (gl != null) {
        svt.destroy(gl);
      }
      // The underlying SegmentationVolume is owned by the SegSpecialElement's per-image-volume
      // cache; only the GL texture is released here.
    }
  }

  public void setVolumePreset(Preset preset) {
    this.volumePreset = Objects.requireNonNull(preset);
    volumePreset.setRequiredBuilding(true);

    PresetWindowLevel defaultPreset = getVolTexture().getDefaultPreset(volumePreset);
    changePresetWindowLevel(defaultPreset);

    renderingLayer.applyVolumePreset(volumePreset, false);
    eventManager
        .getAction(ActionVol.VOL_SHADING)
        .ifPresent(a -> a.setSelectedWithoutTriggerAction(volumePreset.isShade()));
    display();
  }

  protected void changePresetWindowLevel(PresetWindowLevel p) {
    setPresetWindowLevel(p, false);
    eventManager.getAction(ActionW.PRESET).ifPresent(a -> a.setSelectedItemWithoutTriggerAction(p));
    eventManager
        .getAction(ActionW.LUT_SHAPE)
        .ifPresent(a -> a.setSelectedItemWithoutTriggerAction(p.getLutShape()));

    eventManager.applyDefaultWindowLevel(this);
  }

  public Preset getVolumePreset() {
    return volumePreset;
  }

  @Override
  public void copyActionWState(HashMap<String, Object> actionsInView) {
    actionsInView.putAll(this.actionsInView);
  }

  @Override
  public ImageViewerEventManager<DicomImageElement> getEventManager() {
    return eventManager;
  }

  @Override
  public void updateSynchState() {
    SynchData synchData = (SynchData) actionsInView.get(ActionW.SYNCH_LINK.cmd());
    ImageViewerPlugin<DicomImageElement> container = eventManager.getSelectedView2dContainer();
    boolean multiView = container != null && container.getImagePanels().size() > 1;

    // Hide the per-view sync button when nothing can be synced — either no SynchData attached or
    // the layout has a single view (no other view to mirror).
    if (synchData == null || !multiView) {
      if (synchButton != null) {
        synchButton.setVisible(false);
      }
      repaint();
      return;
    }
    if (synchButton == null) {
      synchButton = new SynchViewButton(this::showSyncPopup);
      synchButton.setPosition(GridBagConstraints.SOUTHEAST);
      getViewButtons().add(synchButton);
    }
    synchButton.setState(
        synchData.isSynchActivated() ? SynchData.SyncState.ON : SynchData.SyncState.OFF);
    synchButton.setVisible(true);
    repaint();
  }

  /**
   * Invoked when the user clicks the auto-sync overlay button: shows the per-view sync popup at the
   * click location. Same content as the right-click "Volume sync" submenu, presented as a
   * standalone popup with an additional auto-sync on/off toggle at the top.
   */
  private void showSyncPopup(java.awt.Component invoker, int x, int y) {
    SynchData sd = (SynchData) actionsInView.get(ActionW.SYNCH_LINK.cmd());
    if (sd == null) {
      return;
    }
    ImageViewerPlugin<DicomImageElement> container = eventManager.getSelectedView2dContainer();
    if (container == null) {
      return;
    }

    SynchOptionsCheckBoxGroup group = new SynchOptionsCheckBoxGroup(container.getSyncOptions());
    JPopupMenu popup = group.buildPopupMenu(sd.getActions(), this::onSyncOptionToggled);

    // Top auto-sync on/off toggle.
    JCheckBoxMenuItem syncToggle =
        new JCheckBoxMenuItem(
            org.weasis.core.Messages.getString("ViewerToolBar.synch_this_view"),
            sd.isSynchActivated());
    syncToggle.setToolTipText(
        org.weasis.core.Messages.getString("ViewerToolBar.synch_this_view_tooltip"));
    syncToggle.addActionListener(e -> toggleAutoSync(sd, syncToggle.isSelected()));
    popup.insert(syncToggle, 0);
    popup.insert(new JPopupMenu.Separator(), 1);

    popup.addSeparator();
    popup.add(buildApplyToAllItem(sd, container));

    popup.show(invoker, x, y);
  }

  private void toggleAutoSync(SynchData sd, boolean enabled) {
    // Per-view toggle: only mutate this view's state and mark non-original so a subsequent
    // refresh (updateAllListeners → getOrCreateSynchData) preserves it. Do NOT touch SYNCH_MODE
    // — that would trigger the master toggle and propagate to every other view.
    sd.setOriginal(false);
    sd.setAutoSyncState(enabled ? SynchData.SyncState.ON : SynchData.SyncState.OFF);
    refreshAllListeners();
  }

  private void refreshAllListeners() {
    ComboItemListener<SynchView> synchAction = eventManager.getAction(ActionW.SYNCH).orElse(null);
    SynchView selected =
        synchAction != null && synchAction.getSelectedItem() instanceof SynchView sv
            ? sv
            : SynchView.DEFAULT_STACK;
    eventManager.updateAllListeners(eventManager.getSelectedView2dContainer(), selected);
  }

  private void onSyncOptionToggled(String cmd, boolean selected) {
    SynchData sd = (SynchData) actionsInView.get(ActionW.SYNCH_LINK.cmd());
    if (sd != null) {
      sd.getActions().put(cmd, selected);
      repaint();
    }
  }

  private JMenuItem buildApplyToAllItem(
      SynchData source, ImageViewerPlugin<DicomImageElement> container) {
    JMenuItem applyToAll =
        new JMenuItem(org.weasis.core.Messages.getString("ViewerToolBar.synch_apply_to_all"));
    applyToAll.addActionListener(e -> applySyncOptionsToOtherViews(source, container));
    return applyToAll;
  }

  /**
   * Copy this view's sync actions map to every other view in the container. All 3D views show the
   * same volume so no FrameOfReferenceUID filter is applied; views with sync deactivated are still
   * updated so the user gets a consistent map when they re-enable.
   */
  private void applySyncOptionsToOtherViews(
      SynchData source, ImageViewerPlugin<DicomImageElement> container) {
    if (source == null || container == null) {
      return;
    }
    Map<String, Boolean> snapshot = new HashMap<>(source.getActions());
    for (ViewCanvas<DicomImageElement> v : container.getImagePanels()) {
      if (v == this) {
        continue;
      }
      SynchData target = (SynchData) v.getActionValue(ActionW.SYNCH_LINK.cmd());
      if (target == null) {
        continue;
      }
      target.getActions().clear();
      target.getActions().putAll(snapshot);
      v.getJComponent().repaint();
    }
  }

  @Override
  public PixelInfo getPixelInfo(Point p) {
    return null;
  }

  @Override
  public Panner<DicomImageElement> getPanner() {
    return null;
  }

  @Override
  public void setSeries(MediaSeries<DicomImageElement> series) {
    // Do nothing, use addSeries instead
  }

  @Override
  public void setSeries(MediaSeries<DicomImageElement> newSeries, DicomImageElement selectedMedia) {
    // Do nothing, use addSeries instead
  }

  @Override
  public LayerAnnotation getInfoLayer() {
    return infoLayer;
  }

  @Override
  public int getTileOffset() {
    return 0;
  }

  @Override
  public void setTileOffset(int tileOffset) {}

  @Override
  public final void center() {
    setCenter(0.0, 0.0);
  }

  @Override
  public final void setCenter(Double modelOffsetX, Double modelOffsetY) {
    // Only apply when the panel size is not zero.
    if (getWidth() != 0 && getHeight() != 0) {
      getViewModel().setModelOffset(modelOffsetX, modelOffsetY);
      //  if (viewType == ViewType.VOLUME3D) {
      resetPan();
      //   }
    }
  }

  @Override
  public void moveOrigin(PanPoint point) {
    if (point == null) {
      return;
    }
    PanPoint.State state = point.getState();
    if (PanPoint.State.DRAGSTART.equals(state)) {
      // Anchor the pan reference point so the first DRAGGING delta is computed against the
      // press position rather than this view's stale prevMousePos (which would shift the
      // image visibly when sync is on).
      camera.anchorMouse(point);
    } else if (PanPoint.State.DRAGGING.equals(state)) {
      camera.translate(point);
    }
  }

  @Override
  public Comparator<DicomImageElement> getCurrentSortComparator() {
    return null;
  }

  @Override
  public void setSelected(Boolean selected) {
    setBorder(selected ? focusBorder : viewBorder);
    // Remove the selection of graphics
    graphicManager.setSelectedGraphic(null);
    // Throws to the tool listener the current graphic selection.
    graphicManager.fireGraphicsSelectionChanged(getImageLayer());

    if (selected && getSeries() != null) {
      AuditLog.LOGGER.info("select:series nb:{}", getSeries().getSeriesNumber());
    }
  }

  @Override
  public Font getLayerFont() {
    Font font = FontItem.DEFAULT_SEMIBOLD.getFont();
    return DefaultView2d.getLayerFont(getFontMetrics(font), getWidth());
  }

  @Override
  public void setDrawingsVisibility(Boolean visible) {}

  @Override
  public Object getLensActionValue(String action) {
    return null;
  }

  @Override
  public void changeZoomInterpolation(Interpolation interpolation) {}

  @Override
  public OpManager getDisplayOpManager() {
    return null;
  }

  @Override
  public void disableMouseAndKeyListener() {
    ViewCanvas.super.disableMouseAndKeyListener(this);
  }

  @Override
  public void iniDefaultMouseListener() {
    // The Alt + left drag lasso comes first so the mouse tools see its events as consumed.
    this.addMouseListener(altLassoHandler);
    this.addMouseMotionListener(altLassoHandler);
    // focus listener is always on
    this.addMouseListener(focusHandler);
    this.addMouseMotionListener(focusHandler);
    this.addMouseWheelListener(focusHandler);
  }

  @Override
  public void iniDefaultKeyListener() {
    this.addKeyListener(this);
  }

  @Override
  public int getPointerType() {
    return pointerType;
  }

  @Override
  public void setPointerType(int pointerType) {
    this.pointerType = pointerType;
  }

  @Override
  public void addPointerType(int i) {
    this.pointerType |= i;
  }

  @Override
  public void resetPointerType(int i) {
    this.pointerType &= ~i;
  }

  @Override
  public Point2D getHighlightedPosition() {
    return highlightedPosition;
  }

  @Override
  public List<Action> getExportActions() {
    return View3dSnapshot.getExportActions(this);
  }

  public MouseActionAdapter getMouseAdapter(String command) {
    if (command.equals(ActionW.CONTEXTMENU.cmd())) {
      return contextMenuHandler;
    } else if (command.equals(ActionW.WINLEVEL.cmd())) {
      return getAction(ActionW.LEVEL);
    }

    Optional<Feature<? extends ActionState>> actionKey = eventManager.getActionKey(command);
    if (actionKey.isEmpty()) {
      return null;
    }

    if (actionKey.get().isDrawingAction()) {
      return graphicMouseHandler;
    }
    Optional<? extends ActionState> actionState = eventManager.getAction(actionKey.get());
    if (actionState.isPresent() && actionState.get() instanceof MouseActionAdapter listener) {
      return listener;
    }
    return null;
  }

  @Override
  public void resetMouseAdapter() {
    ViewCanvas.super.resetMouseAdapter();

    // reset context menu that is a field of this instance
    contextMenuHandler.setButtonMaskEx(0);
    graphicMouseHandler.setButtonMaskEx(0);
  }

  @Override
  public void resetZoom() {
    zoom(0.0);
  }

  @Override
  public void resetPan() {
    camera.resetPan();
  }

  @Override
  public void reset() {
    ImageViewerPlugin<DicomImageElement> pane = eventManager.getSelectedView2dContainer();
    if (pane != null) {
      pane.resetMaximizedSelectedImagePane(this);
    }

    initActionWState();

    View c = Camera.getDefaultOrientation();
    camera.clearPan();
    camera.set(c.position(), c.rotation(), fitZoomForVisible(), false);
    renderingLayer.setEnableRepaint(false);
    renderingLayer.setQuality(getDefaultQuality());
    renderingLayer.setOpacity(1.0);
    renderingLayer.setInvertLut(false);
    setVolumePreset(volumePreset);
    renderingLayer.setEnableRepaint(true);
    renderingLayer.fireLayerChanged();
    eventManager.updateComponentsListener(this);
  }

  private final List<ViewButton> viewButtons = new java.util.ArrayList<>();

  @Override
  public List<ViewButton> getViewButtons() {
    return viewButtons;
  }

  @Override
  public void closeLens() {}

  @Override
  public void updateCanvas(boolean triggerViewModelChangeListeners) {}

  @Override
  public void updateGraphicSelectionListener(ImageViewerPlugin<DicomImageElement> viewerPlugin) {}

  @Override
  public boolean requiredTextAntialiasing() {
    return false;
  }

  @Override
  public JPopupMenu buildGraphicContextMenu(MouseEvent evt, List<Graphic> selected) {
    return null;
  }

  @Override
  public JPopupMenu buildContextMenu(MouseEvent evt) {
    JPopupMenu popupMenu = buildLeftMouseActionMenu();

    int count = popupMenu.getComponentCount();

    if (eventManager instanceof EventManager manager) {
      GuiUtils.addItemToMenu(popupMenu, manager.getLutMenu(null));
      GuiUtils.addItemToMenu(popupMenu, manager.getLutShapeMenu(null));
      count = addSeparatorToPopupMenu(popupMenu, count);

      GuiUtils.addItemToMenu(popupMenu, manager.getViewTypeMenu(null));
      GuiUtils.addItemToMenu(popupMenu, manager.getShadingMenu(null));
      GuiUtils.addItemToMenu(popupMenu, manager.getSProjectionMenu(null));
      count = addSeparatorToPopupMenu(popupMenu, count);

      GuiUtils.addItemToMenu(popupMenu, manager.getMprCutMenu(null));
      count = addSeparatorToPopupMenu(popupMenu, count);

      GuiUtils.addItemToMenu(popupMenu, manager.getZoomMenu(null));
      GuiUtils.addItemToMenu(popupMenu, manager.getOrientationMenu(null));
      count = addSeparatorToPopupMenu(popupMenu, count);

      GuiUtils.addItemToMenu(popupMenu, buildSyncOptionsMenu());
      count = addSeparatorToPopupMenu(popupMenu, count);

      getExportActions().forEach(popupMenu::add);
      count = addSeparatorToPopupMenu(popupMenu, count);

      GuiUtils.addItemToMenu(popupMenu, manager.getResetMenu(null));
    }
    return popupMenu;
  }

  /**
   * Per-view sync options submenu shown in the right-click context menu. Reads/writes this view's
   * own {@link SynchData} (attached by {@code EventManager.updateAllListeners}); the Apply-to-all
   * entry then copies this view's actions map to every other view in the container.
   */
  private JMenu buildSyncOptionsMenu() {
    ImageViewerPlugin<DicomImageElement> container = eventManager.getSelectedView2dContainer();
    SynchData sd = (SynchData) actionsInView.get(ActionW.SYNCH_LINK.cmd());
    if (container == null || sd == null) {
      return null;
    }
    SynchOptionsCheckBoxGroup group = new SynchOptionsCheckBoxGroup(container.getSyncOptions());
    JMenu menu = new JMenu(org.weasis.core.Messages.getString("ActionW.synch"));
    group.addStayOpenItemsTo(menu, sd.getActions(), this::onSyncOptionToggled);
    menu.addSeparator();
    menu.add(buildApplyToAllItem(sd, container));
    return menu;
  }

  @Override
  public boolean hasValidContent() {
    return volTexture != null && volTexture.isReadyForDisplay();
  }

  @Override
  public void focusGained(FocusEvent e) {}

  @Override
  public void focusLost(FocusEvent e) {}

  @Override
  public void keyTyped(KeyEvent e) {}

  @Override
  public void keyPressed(KeyEvent e) {
    if (!Shortcuts3d.handle(this, e)) {
      defaultKeyPressed(eventManager, e);
    }
  }

  @Override
  public void keyReleased(KeyEvent e) {}

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    String propertyName = evt.getPropertyName();
    if (propertyName != null) {
      if (propertyName.equals(ActionW.SYNCH.cmd())) {
        propertyChange((SynchEvent) evt.getNewValue());
      }
    }
  }

  private void propertyChange(final SynchEvent synch) {
    {
      SynchData synchData = (SynchData) actionsInView.get(ActionW.SYNCH_LINK.cmd());
      SynchData issuerSyncData =
          synch.getView() != null
              ? (SynchData) synch.getView().getActionsInView().get(ActionW.SYNCH_LINK.cmd())
              : null;
      boolean isSource = synch.getView() == this;
      if (!isSource) {
        if (synchData != null && !synchData.isSynchActivated()) {
          return;
        }
        if (issuerSyncData != null && !issuerSyncData.isSynchActivated()) {
          return;
        }
      }
      // Progressive mode for VR
      boolean forceRepaint = camera.isAdjusting() != synch.isValueIsAdjusting();
      camera.setAdjusting(synch.isValueIsAdjusting());

      for (Entry<String, Object> entry : synch.getEvents().entrySet()) {
        String command = entry.getKey();
        final Object val = entry.getValue();
        // The originator always applies its own action (otherwise unchecking e.g. W/L in the
        // popup would also break the slider locally). For target views, both the issuer's and
        // the receiver's sync option must be enabled for this action.
        if (!isSource
            && ((synchData != null && !synchData.isActionEnable(command))
                || (issuerSyncData != null && !issuerSyncData.isActionEnable(command)))) {
          continue;
        }
        if (command.equals(ActionW.WINDOW.cmd())) {
          windowBeforePreview = null; // the user's window ends a threshold preview
          renderingLayer.setWindowWidth(((Double) val).intValue(), forceRepaint);
        } else if (command.equals(ActionW.LEVEL.cmd())) {
          windowBeforePreview = null;
          renderingLayer.setWindowCenter(((Double) val).intValue(), forceRepaint);
        } else if (command.equals(ActionW.PRESET.cmd())) {
          if (val instanceof PresetWindowLevel preset) {
            setPresetWindowLevel(preset, true);
          } else if (val == null) {
            setActionsInView(ActionW.PRESET.cmd(), val, false);
          }
        } else if (command.equals(ActionW.LUT_SHAPE.cmd())) {
          if (val instanceof LutShape lutShape) {
            renderingLayer.setLutShape(lutShape);
          }
        } else if (command.equals(ActionW.ROTATION.cmd()) && val instanceof Integer rotation) {
          setRotation(rotation);
        } else if (command.equals(ActionW.ROTATION.cmd()) && val instanceof Quaterniond quat) {
          // Arcball path: the source view already applied the rotation via Camera.rotate(), so
          // re-applying here would only trigger a redundant repaint. Targets adopt the full 3D
          // rotation that the integer slider angle cannot represent.
          if (!isSource) {
            camera.getRotation().set(quat);
            camera.updateCameraTransform();
          }
        } else if (command.equals(ActionW.RESET.cmd())) {
          reset();
        } else if (command.equals(ActionW.ZOOM.cmd())) {
          double value = (Double) val;
          // Special Cases: -200.0 => best fit, -100.0 => real world size
          if (value != -200.0 && value != -100.0) {
            zoom(value);
          } else {
            Object zoomType = actionsInView.get(ViewCanvas.ZOOM_TYPE_CMD);
            actionsInView.put(
                ViewCanvas.ZOOM_TYPE_CMD, value == -100.0 ? ZoomType.REAL : ZoomType.BEST_FIT);
            zoom(0.0);
            actionsInView.put(ViewCanvas.ZOOM_TYPE_CMD, zoomType);
          }
        } else if (command.equals(ActionW.PAN.cmd())) {
          if (val instanceof PanPoint panPoint) {
            moveOrigin(panPoint);
          }
        } else if (command.equals(ActionW.FLIP.cmd())) {
          actionsInView.put(ActionW.FLIP.cmd(), val);
          // LangUtil.getNULLtoFalse((Boolean) val);
          //   updateAffineTransform();
          repaint();
        } else if (command.equals(ActionVol.VOL_PRESET.cmd())) {
          setVolumePreset((Preset) val);
        } else if (command.equals(ActionW.INVERT_LUT.cmd())) {
          if (val instanceof Boolean invertLut) {
            if (volumePreset != null && renderingLayer.isInvertLut() != invertLut) {
              volumePreset.setRequiredBuilding(true);
            }
            renderingLayer.setInvertLut(invertLut);
          }
        } else if (command.equals(ActionW.SPATIAL_UNIT.cmd())) {
          actionsInView.put(command, val);

          // TODO update only measure and limit when selected view share graphics
          List<Graphic> list = this.getGraphicManager().getAllGraphics();
          for (Graphic graphic : list) {
            graphic.updateLabel(true, this);
          }
        } else if (command.equals(ActionVol.RENDERING_TYPE.cmd())) {
          if (val instanceof RenderingType type) {
            RenderingType oldType = renderingLayer.getRenderingType();
            if (type != oldType) {
              renderingLayer.setEnableRepaint(false);
              renderingLayer.setRenderingType(type);
              renderingLayer.setEnableRepaint(true);
              display();
            }
          }
        } else if (command.equals(ActionVol.VOL_AXIS.cmd())) {
          if (val instanceof Axis axis) {
            camera.setRotationAxis(axis);
          }
        } else if (command.equals(ActionVol.VOL_QUALITY.cmd())) {
          if (val instanceof Integer quality) {
            renderingLayer.setQuality(quality);
          }
        } else if (command.equals(ActionVol.VOL_OPACITY.cmd())) {
          if (val instanceof Double opacity) {
            renderingLayer.setOpacity(opacity, forceRepaint);
          }
        } else if (command.equals(ActionVol.VOL_SHADING.cmd())) {
          if (val instanceof Boolean shading) {
            renderingLayer.setShading(shading);
          }
        } else if (command.equals(ActionVol.SEG_TYPE.cmd())) {
          if (val instanceof Type type) {
            setSegType(type);
          }
        } else if (command.equals(ActionVol.CROSSHAIR_CUT_MODE.cmd())) {
          if (val instanceof CrosshairCutMode cutMode) {
            mprCrossHairCutMode = cutMode;
            if (cutMode == CrosshairCutMode.NONE) {
              if (volTexture != null) {
                volTexture.unregisterCrossHairRelay();
              }
              mprCrossHairPosition = null;
              mprCrossHairRotation = null;
            } else {
              if (volTexture != null) {
                volTexture.registerCrossHairRelay();
              }
              ensureMprIsOpen();
            }
            display();
          }
        } else if (command.equals(ActionVol.VOL_PROJECTION.cmd())) {
          if (val instanceof Boolean projection) {
            camera.setOrthographicProjection(projection);
          }
        }
      }
    }
  }

  private void ensureMprIsOpen() {
    DicomVolTexture tex = volTexture;
    if (tex == null) {
      return;
    }
    MediaSeries<DicomImageElement> series = tex.getSeries();
    if (series == null) {
      return;
    }
    if (!fireCrossHairIfMprOpen(tex)) {
      // No MPR viewer found – open one.
      MprFactory.getMprAction(series).actionPerformed(null);
    }
  }

  private void syncMprPosition() {
    DicomVolTexture tex = volTexture;
    if (tex == null) {
      return;
    }
    fireCrossHairIfMprOpen(tex);
  }

  private boolean fireCrossHairIfMprOpen(DicomVolTexture tex) {
    Volume<?, ?> texVolume = tex.getVolume();
    if (texVolume == null || texVolume.getStack() == null) {
      return false;
    }
    OriginalStack texStack = texVolume.getStack();
    boolean texIsBasic = texVolume.isBasic();
    List<?> plugins = GuiUtils.getUICore().getViewerPlugins();
    synchronized (plugins) {
      for (Object plugin : plugins) {
        if (plugin instanceof MprContainer mpr) {
          Volume<?, ?> mprVolume = mpr.getMprController().getVolume();
          if (mprVolume != null
              && texStack.equals(mprVolume.getStack())
              && texIsBasic == mprVolume.isBasic()) {
            mpr.getMprController().fireCrossHairChanged();
            return true;
          }
        }
      }
    }
    return false;
  }

  private void setPresetWindowLevel(PresetWindowLevel preset, boolean repaint) {
    if (preset != null) {
      windowBeforePreview = null;
      renderingLayer.setEnableRepaint(false);
      renderingLayer.setWindowWidth((int) preset.getWindow());
      renderingLayer.setWindowCenter((int) preset.getLevel());
      renderingLayer.setLutShape(preset.getLutShape());
      renderingLayer.setEnableRepaint(true);
      if (repaint) {
        renderingLayer.fireLayerChanged();
      }
    }
    setActionsInView(ActionW.PRESET.cmd(), preset, false);
  }

  @Override
  public MediaSeries<DicomImageElement> getSeries() {
    DicomVolTexture vol = volTexture;
    if (vol != null) {
      return vol.getSeries();
    }
    return null;
  }

  @Override
  public int getFrameIndex() {
    return 0;
  }

  @Override
  public void drawLayers(
      Graphics2D g2d, AffineTransform transform, AffineTransform inverseTransform) {}

  @Override
  public ImageLayer<DicomImageElement> getImageLayer() {
    return null;
  }

  @Override
  public MeasurableLayer getMeasurableLayer() {
    return null;
  }

  @Override
  public DicomImageElement getImage() {
    return null;
  }

  @Override
  public PlanarImage getSourceImage() {
    return null;
  }

  @Override
  public void handleLayerChanged(ImageLayer<DicomImageElement> layer) {}
}
