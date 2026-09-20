package com.lumastack.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PHOTOS = 42, WRITE_STORAGE = 43, CAMERA_PERMISSION = 44;
    private static final int NAVY = Color.rgb(6, 13, 22);
    private static final int PANEL = Color.rgb(14, 25, 38);
    private static final int INK = Color.rgb(239, 246, 255);
    private static final int MUTED = Color.rgb(145, 164, 188);
    private static final int CYAN = Color.rgb(75, 220, 255);

    private final List<Uri> photos = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NumberPicker countPicker;
    private TextView status, frameCounter, resultStats;
    private Button captureButton, importButton, stackButton, compareButton, shareButton;
    private ProgressBar progress;
    private ZoomImageView resultPreview;
    private TextureView cameraPreview;
    private View shutterFlash, focusRing;
    private FrameLayout rootView, cameraCard;
    private LinearLayout controlsCard, appearanceCard, resultActions;
    private Switch glassSwitch;
    private int targetCount;
    private boolean capturing;
    private boolean glassEnabled;
    private boolean showingReference;
    private Bitmap finalBitmap, referenceBitmap;
    private Uri savedResultUri;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraSession;
    private CaptureRequest.Builder previewRequest;
    private ImageReader imageReader;
    private String cameraId;
    private Size captureSize;
    private int sensorOrientation;
    private Rect sensorArray;
    private int maxAfRegions, maxAeRegions;
    private Range<Integer> exposureRange = new Range<>(0, 0);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        glassEnabled = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("liquid_glass", true);
        buildUi();
        showIntro();
    }

    @Override protected void onResume() {
        super.onResume();
        startCameraThread();
        if (cameraPreview.isAvailable()) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private void buildUi() {
        rootView = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(32));
        scroll.addView(page);
        rootView.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = label("LumaStack", 28, INK);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        brandRow.addView(pill("●  OFFLINE", CYAN, Color.argb(35, 75, 220, 255)));
        page.addView(brandRow);
        TextView subtitle = label("Computational photography, entirely on your phone", 14, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        page.addView(subtitle);

        cameraCard = new FrameLayout(this);
        cameraCard.setClipToOutline(true);
        cameraPreview = new TextureView(this);
        cameraPreview.setSurfaceTextureListener(surfaceListener);
        cameraPreview.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !capturing)
                focusAt(event.getX(), event.getY());
            return true;
        });
        cameraCard.addView(cameraPreview, new FrameLayout.LayoutParams(-1, -1));
        shutterFlash = new View(this);
        shutterFlash.setBackgroundColor(Color.WHITE);
        shutterFlash.setAlpha(0f);
        cameraCard.addView(shutterFlash, new FrameLayout.LayoutParams(-1, -1));
        focusRing = new View(this);
        GradientDrawable focusDrawable = new GradientDrawable();
        focusDrawable.setShape(GradientDrawable.OVAL);
        focusDrawable.setColor(Color.TRANSPARENT);
        focusDrawable.setStroke(dp(2), CYAN);
        focusRing.setBackground(focusDrawable);
        focusRing.setAlpha(0f);
        cameraCard.addView(focusRing, new FrameLayout.LayoutParams(dp(64), dp(64)));
        frameCounter = pill("CAMERA STARTING", Color.WHITE, Color.argb(145, 4, 10, 18));
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        counterParams.setMargins(dp(14), dp(14), 0, 0);
        cameraCard.addView(frameCounter, counterParams);
        TextView guide = pill("AUTO BRACKET  •  HOLD STEADY", INK, Color.argb(160, 4, 10, 18));
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        guideParams.setMargins(0, 0, 0, dp(14));
        cameraCard.addView(guide, guideParams);
        page.addView(cameraCard, new LinearLayout.LayoutParams(-1, dp(410)));

        controlsCard = new LinearLayout(this);
        controlsCard.setOrientation(LinearLayout.VERTICAL);
        controlsCard.setPadding(dp(18), dp(16), dp(18), dp(18));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-1, -2);
        settingsParams.topMargin = dp(14);
        page.addView(controlsCard, settingsParams);
        LinearLayout countRow = new LinearLayout(this);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout countText = new LinearLayout(this);
        countText.setOrientation(LinearLayout.VERTICAL);
        TextView countTitle = label("Stack depth", 17, INK);
        countTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        countText.addView(countTitle);
        countText.addView(label("More frames improve noise and highlight detail", 12, MUTED));
        countRow.addView(countText, new LinearLayout.LayoutParams(0, -2, 1));
        countPicker = new NumberPicker(this);
        countPicker.setMinValue(2);
        countPicker.setMaxValue(12);
        countPicker.setValue(5);
        countPicker.setWrapSelectorWheel(false);
        countPicker.setOnValueChangedListener((picker, oldValue, newValue) -> refreshButtons());
        countRow.addView(countPicker, new LinearLayout.LayoutParams(dp(88), dp(110)));
        controlsCard.addView(countRow);
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setGravity(Gravity.CENTER);
        presetRow.addView(presetButton("3x", 3));
        presetRow.addView(presetButton("5x", 5));
        presetRow.addView(presetButton("8x", 8));
        presetRow.addView(presetButton("12x", 12));
        controlsCard.addView(presetRow);
        captureButton = premiumButton("Capture stack automatically", CYAN, Color.rgb(4, 27, 38), this::startSeries);
        controlsCard.addView(captureButton);
        importButton = premiumButton("Choose photos from Gallery", Color.rgb(45, 63, 82), INK, this::choosePhotos);
        controlsCard.addView(importButton);
        stackButton = premiumButton("Fuse, grade and save", Color.rgb(145, 98, 255), Color.WHITE, this::stack);
        controlsCard.addView(stackButton);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(6));
        progressParams.topMargin = dp(18);
        controlsCard.addView(progress, progressParams);
        status = label("Camera preview stays inside LumaStack. Tap capture and hold steady.", 14, MUTED);
        status.setPadding(0, dp(15), 0, 0);
        controlsCard.addView(status);

        appearanceCard = new LinearLayout(this);
        appearanceCard.setOrientation(LinearLayout.VERTICAL);
        appearanceCard.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams appearanceParams = new LinearLayout.LayoutParams(-1, -2);
        appearanceParams.topMargin = dp(14);
        page.addView(appearanceCard, appearanceParams);
        LinearLayout appearanceRow = new LinearLayout(this);
        appearanceRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout appearanceText = new LinearLayout(this);
        appearanceText.setOrientation(LinearLayout.VERTICAL);
        TextView appearanceTitle = label("Liquid Glass", 17, INK);
        appearanceTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appearanceText.addView(appearanceTitle);
        appearanceText.addView(label("Frosted layers, light edges and fluid depth", 12, MUTED));
        appearanceRow.addView(appearanceText, new LinearLayout.LayoutParams(0, -2, 1));
        glassSwitch = new Switch(this);
        glassSwitch.setChecked(glassEnabled);
        glassSwitch.setContentDescription("Turn Liquid Glass appearance on or off");
        glassSwitch.setOnCheckedChangeListener((button, checked) -> {
            glassEnabled = checked;
            getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("liquid_glass", checked).apply();
            applyAppearance();
        });
        appearanceRow.addView(glassSwitch);
        appearanceCard.addView(appearanceRow);

        resultPreview = new ZoomImageView(this);
        resultPreview.setContentDescription("Finished stacked image. Pinch to zoom and drag to inspect.");
        resultPreview.setVisibility(View.GONE);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(-1, dp(320));
        resultParams.topMargin = dp(14);
        page.addView(resultPreview, resultParams);
        resultActions = new LinearLayout(this);
        resultActions.setGravity(Gravity.CENTER);
        compareButton = compactButton("View original", Color.rgb(45, 63, 82), INK, this::toggleComparison);
        shareButton = compactButton("Share result", Color.rgb(30, 115, 132), Color.WHITE, this::shareResult);
        resultActions.addView(compareButton);
        resultActions.addView(shareButton);
        resultActions.setVisibility(View.GONE);
        page.addView(resultActions);
        resultStats = label("", 12, MUTED);
        resultStats.setPadding(dp(6), dp(12), dp(6), 0);
        resultStats.setVisibility(View.GONE);
        page.addView(resultStats);
        TextView privacy = label("PRIVATE BY DESIGN  •  NO ACCOUNT  •  NO CLOUD  •  NO API", 11, MUTED);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLetterSpacing(0.08f);
        privacy.setPadding(0, dp(22), 0, 0);
        page.addView(privacy);
        setContentView(rootView);
        applyAppearance();
        refreshButtons();
    }

    private void showIntro() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(NAVY);
        LinearLayout lockup = new LinearLayout(this);
        lockup.setOrientation(LinearLayout.VERTICAL);
        lockup.setGravity(Gravity.CENTER);
        TextView title = label("LumaStack", 46, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.04f);
        TextView line = label("LIGHT • ALIGNED • REFINED", 12, CYAN);
        line.setGravity(Gravity.CENTER);
        line.setLetterSpacing(0.22f);
        line.setPadding(0, dp(10), 0, dp(34));
        TextView credit = label("Made by Nitir, using ChatGPT Codex GPT-5.6 Sol Medium", 13, MUTED);
        credit.setGravity(Gravity.CENTER);
        lockup.addView(title);
        lockup.addView(line);
        lockup.addView(credit);
        overlay.addView(lockup, new FrameLayout.LayoutParams(-1, -1));
        ((ViewGroup) findViewById(android.R.id.content)).addView(overlay);
        title.setAlpha(0f); title.setScaleX(0.86f); title.setScaleY(0.86f); title.setTranslationY(dp(18));
        line.setAlpha(0f); line.setTranslationY(dp(12));
        credit.setAlpha(0f); credit.setTranslationY(dp(16));
        AccelerateDecelerateInterpolator smooth = new AccelerateDecelerateInterpolator();
        title.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(900).setInterpolator(smooth).start();
        line.animate().alpha(1f).translationY(0).setStartDelay(420).setDuration(650).setInterpolator(smooth).start();
        credit.animate().alpha(1f).translationY(0).setStartDelay(760).setDuration(700).setInterpolator(smooth)
                .withEndAction(() -> overlay.animate().alpha(0f).setStartDelay(650).setDuration(650)
                        .withEndAction(() -> ((ViewGroup) overlay.getParent()).removeView(overlay)).start()).start();
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { openCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
    };

    private void startCameraThread() {
        cameraThread = new HandlerThread("LumaStackCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try { cameraThread.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (cameraDevice != null || cameraHandler == null || !cameraPreview.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorOrientation = orientation == null ? 90 : orientation;
                    sensorArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    Integer afRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                    Integer aeRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                    maxAfRegions = afRegions == null ? 0 : afRegions;
                    maxAeRegions = aeRegions == null ? 0 : aeRegions;
                    Range<Integer> range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                    if (range != null) exposureRange = range;
                    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    captureSize = chooseCaptureSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));
                    break;
                }
            }
            if (cameraId == null) throw new IllegalStateException("No rear camera found");
            imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
            frameCounter.setText("OPENING CAMERA");
        } catch (Exception e) { status.setText("Camera unavailable: " + e.getMessage()); }
    }

    private Size chooseCaptureSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        Size best = sizes[0];
        long bestPixels = 0;
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (pixels <= 12_000_000L && pixels > bestPixels) {
                best = size;
                bestPixels = pixels;
            }
        }
        return best;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) { cameraDevice = camera; createPreviewSession(); }
        @Override public void onDisconnected(CameraDevice camera) { camera.close(); cameraDevice = null; }
        @Override public void onError(CameraDevice camera, int error) {
            camera.close(); cameraDevice = null;
            runOnUiThread(() -> status.setText("Camera error " + error + ". Reopen the app to retry."));
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            if (texture == null || cameraDevice == null) return;
            texture.setDefaultBufferSize(1920, 1080);
            Surface previewSurface = new Surface(texture);
            previewRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(previewSurface);
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            cameraSession = session;
                            try { session.setRepeatingRequest(previewRequest.build(), null, cameraHandler); }
                            catch (CameraAccessException e) { showCameraError(e); }
                            runOnUiThread(() -> { frameCounter.setText("LIVE • READY"); refreshButtons(); });
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> status.setText("Could not start the camera preview."));
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) { showCameraError(e); }
    }

    private void focusAt(float previewX, float previewY) {
        if (cameraSession == null || previewRequest == null || sensorArray == null
                || cameraPreview.getWidth() == 0 || cameraPreview.getHeight() == 0) return;
        int sensorX = sensorArray.left + Math.round(previewX / cameraPreview.getWidth() * sensorArray.width());
        int sensorY = sensorArray.top + Math.round(previewY / cameraPreview.getHeight() * sensorArray.height());
        int half = Math.max(40, Math.min(sensorArray.width(), sensorArray.height()) / 14);
        Rect area = new Rect(
                Math.max(sensorArray.left, sensorX - half), Math.max(sensorArray.top, sensorY - half),
                Math.min(sensorArray.right, sensorX + half), Math.min(sensorArray.bottom, sensorY + half));
        MeteringRectangle metering = new MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX);
        try {
            if (maxAfRegions > 0) previewRequest.set(CaptureRequest.CONTROL_AF_REGIONS,
                    new MeteringRectangle[]{metering});
            if (maxAeRegions > 0) previewRequest.set(CaptureRequest.CONTROL_AE_REGIONS,
                    new MeteringRectangle[]{metering});
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            cameraSession.capture(previewRequest.build(), null, cameraHandler);
            frameCounter.setText("FOCUSING");
            focusRing.setX(previewX - dp(32)); focusRing.setY(previewY - dp(32));
            focusRing.setScaleX(.65f); focusRing.setScaleY(.65f); focusRing.setAlpha(1f);
            focusRing.animate().scaleX(1f).scaleY(1f).alpha(.15f).setDuration(700).start();
            cameraHandler.postDelayed(() -> {
                try {
                    previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                    previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    cameraSession.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
                    runOnUiThread(() -> frameCounter.setText("LIVE • READY"));
                } catch (Exception ignored) { }
            }, 650);
        } catch (CameraAccessException e) { showCameraError(e); }
    }

    private void startSeries() {
        if (cameraSession == null || imageReader == null) {
            status.setText("Camera is still starting. Try again in a moment.");
            return;
        }
        photos.clear();
        targetCount = countPicker.getValue();
        capturing = true;
        hideResultInspection();
        status.setText("Auto-capturing an exposure bracket. Hold steady…");
        refreshButtons();
        captureNext();
    }

    private void captureNext() {
        if (!capturing || cameraDevice == null || cameraSession == null) return;
        try {
            int index = photos.size();
            CaptureRequest.Builder shot = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            shot.addTarget(imageReader.getSurface());
            shot.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            shot.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            int span = Math.min(4, Math.max(Math.abs(exposureRange.getLower()), Math.abs(exposureRange.getUpper())));
            int compensation = targetCount <= 1 ? 0 : Math.round(-span + (2f * span * index / (targetCount - 1f)));
            compensation = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), compensation));
            shot.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation);
            shot.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            frameCounter.setText("CAPTURING  " + (index + 1) + " / " + targetCount);
            cameraSession.capture(shot.build(), null, cameraHandler);
        } catch (CameraAccessException e) { capturing = false; showCameraError(e); }
    }

    private int jpegOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
        return (sensorOrientation + degrees) % 360;
    }

    private void onImageAvailable(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create capture folder.");
            File file = new File(dir, "auto_" + System.currentTimeMillis() + "_" + photos.size() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            runOnUiThread(() -> {
                photos.add(uri);
                shutterFlash.setAlpha(0.72f);
                shutterFlash.animate().alpha(0f).setDuration(240).start();
                status.setText("Captured " + photos.size() + " of " + targetCount + " frames");
                if (photos.size() < targetCount) cameraPreview.postDelayed(this::captureNext, 420);
                else {
                    capturing = false;
                    frameCounter.setText("STACK READY");
                    status.setText("Bracket captured. Tap Fuse, grade and save.");
                    refreshButtons();
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> { capturing = false; status.setText("Capture failed: " + e.getMessage()); refreshButtons(); });
        }
    }

    private void choosePhotos() {
        targetCount = countPicker.getValue();
        status.setText("Choose up to " + targetCount + " photos. Selecting 2 or more makes a stack ready.");
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                    Math.min(targetCount, MediaStore.getPickImagesMaxLimit()));
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        startActivityForResult(intent, PICK_PHOTOS);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PHOTOS || result != RESULT_OK || data == null) return;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (data.getClipData() != null) {
            int count = Math.min(12, data.getClipData().getItemCount());
            for (int i = 0; i < count; i++) selected.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        ArrayList<Uri> streams = data.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (streams != null) selected.addAll(streams);
        photos.clear();
        for (Uri uri : selected) {
            if (photos.size() == 12) break;
            photos.add(uri);
        }
        for (Uri uri : photos) try {
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        if (photos.size() >= 2) countPicker.setValue(photos.size());
        targetCount = countPicker.getValue();
        frameCounter.setText("IMPORTED  " + photos.size() + " / " + targetCount);
        status.setText(photos.size() >= 2 ? "Gallery stack ready with " + photos.size() + " photos."
                : "Choose at least two photos to create a stack.");
        refreshButtons();
    }

    private void refreshButtons() {
        captureButton.setEnabled(!capturing && cameraSession != null);
        importButton.setEnabled(!capturing);
        importButton.setText("Choose up to " + countPicker.getValue() + " photos from Gallery");
        stackButton.setEnabled(!capturing && photos.size() == countPicker.getValue());
        countPicker.setEnabled(!capturing);
    }

    private void stack() {
        if (photos.size() != countPicker.getValue()) {
            status.setText("Capture or choose exactly " + countPicker.getValue() + " photos.");
            return;
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_STORAGE);
            status.setText("Allow storage access so LumaStack can save the finished photo.");
            return;
        }
        List<Uri> selection = new ArrayList<>(photos);
        hideResultInspection();
        recycleDisplayedResults();
        captureButton.setEnabled(false); importButton.setEnabled(false); stackButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE); progress.setProgress(0);
        frameCounter.setText("FUSING FRAMES");
        status.setText("Aligning detail, balancing exposure and grading color…");
        worker.execute(() -> {
            StackProcessor.Result processed = null;
            try {
                processed = StackProcessor.process(this, selection, (done, total) -> runOnUiThread(() -> {
                    progress.setProgress(Math.round(done * 90f / total));
                    status.setText("Blending frame " + done + " of " + total + "…");
                }));
                Uri saved = save(processed.bitmap);
                StackProcessor.Result display = processed;
                runOnUiThread(() -> {
                    recycleDisplayedResults();
                    finalBitmap = display.bitmap;
                    referenceBitmap = display.reference;
                    savedResultUri = saved;
                    showingReference = false;
                    resultPreview.setImageBitmap(finalBitmap);
                    resultPreview.resetToFit();
                    resultPreview.setVisibility(View.VISIBLE);
                    resultActions.setVisibility(View.VISIBLE);
                    resultStats.setText(formatStats(display, selection.size()));
                    resultStats.setVisibility(View.VISIBLE);
                    compareButton.setText("View original");
                    resultPreview.setAlpha(0f);
                    resultPreview.setTranslationY(dp(18));
                    resultPreview.animate().alpha(1f).translationY(0).setDuration(650).start();
                    progress.setProgress(100); progress.setVisibility(View.GONE);
                    frameCounter.setText("MASTER SAVED");
                    status.setText("Saved to Pictures/LumaStack in full JPEG quality.");
                    refreshButtons();
                });
            } catch (Exception e) {
                if (processed != null) {
                    processed.bitmap.recycle();
                    processed.reference.recycle();
                }
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE); frameCounter.setText("TRY AGAIN");
                    status.setText("Could not stack: " + e.getMessage()); refreshButtons();
                });
            }
        });
    }

    private void toggleComparison() {
        if (finalBitmap == null || referenceBitmap == null) return;
        showingReference = !showingReference;
        resultPreview.setImageBitmap(showingReference ? referenceBitmap : finalBitmap);
        resultPreview.resetToFit();
        compareButton.setText(showingReference ? "View fused result" : "View original");
        frameCounter.setText(showingReference ? "REFERENCE FRAME" : "FUSED MASTER");
    }

    private void shareResult() {
        if (savedResultUri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/jpeg");
        share.putExtra(Intent.EXTRA_STREAM, savedResultUri);
        share.setClipData(ClipData.newRawUri("LumaStack result", savedResultUri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share LumaStack result"));
    }

    private String formatStats(StackProcessor.Result result, int frameCount) {
        int maxX = 0, maxY = 0;
        for (int[] offset : result.offsets) {
            maxX = Math.max(maxX, Math.abs(offset[0]));
            maxY = Math.max(maxY, Math.abs(offset[1]));
        }
        return "COMPUTATIONAL INSPECTOR\n" + frameCount + " frames  •  Reference #"
                + (result.referenceIndex + 1) + "  •  Max alignment " + maxX + "×" + maxY
                + " px\n" + String.format(java.util.Locale.US, "Processed in %.1fs  •  Pictures/LumaStack",
                result.processingMillis / 1000f);
    }

    private void hideResultInspection() {
        if (resultPreview != null) resultPreview.setVisibility(View.GONE);
        if (resultActions != null) resultActions.setVisibility(View.GONE);
        if (resultStats != null) resultStats.setVisibility(View.GONE);
    }

    private void recycleDisplayedResults() {
        if (resultPreview != null) resultPreview.setImageDrawable(null);
        if (finalBitmap != null && !finalBitmap.isRecycled()) finalBitmap.recycle();
        if (referenceBitmap != null && !referenceBitmap.isRecycled()) referenceBitmap.recycle();
        finalBitmap = null; referenceBitmap = null; savedResultUri = null;
    }

    private Uri save(Bitmap bitmap) throws IOException {
        String name = "LumaStack_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LumaStack");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LumaStack");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Cannot create Pictures/LumaStack.");
            values.put(MediaStore.Images.Media.DATA, new File(folder, name).getAbsolutePath());
        }
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create image in Photos.");
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("JPEG export failed.");
        } catch (IOException e) { getContentResolver().delete(uri, null, null); throw e; }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        }
        return uri;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
            else status.setText("Camera access is needed for automatic capture. You can still import photos.");
        } else if (requestCode == WRITE_STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) stack();
            else status.setText("Storage access is needed to save the finished photo on Android 6–9.");
        }
    }

    private void closeCamera() {
        if (cameraSession != null) { cameraSession.close(); cameraSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }

    private void showCameraError(Exception e) {
        runOnUiThread(() -> { status.setText("Camera error: " + e.getMessage()); refreshButtons(); });
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(sp); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView pill(String text, int color, int background) {
        TextView view = label(text, 11, color);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(0.08f);
        view.setPadding(dp(12), dp(7), dp(12), dp(7));
        view.setBackground(roundRect(background, 40));
        return view;
    }

    private Button premiumButton(String text, int background, int foreground, Runnable action) {
        Button button = new Button(this);
        button.setText(text); button.setTextColor(foreground); button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setAllCaps(false);
        button.setTag(background);
        button.setBackground(buttonSurface(background)); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.topMargin = dp(10); button.setLayoutParams(params);
        return button;
    }

    private Button compactButton(String text, int background, int foreground, Runnable action) {
        Button button = new Button(this);
        button.setText(text); button.setTextColor(foreground); button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setAllCaps(false);
        button.setTag(background); button.setBackground(buttonSurface(background));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.setMargins(dp(4), dp(10), dp(4), 0); button.setLayoutParams(params);
        return button;
    }

    private Button presetButton(String text, int count) {
        return compactButton(text, Color.rgb(27, 47, 66), INK, () -> countPicker.setValue(count));
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable glassSurface(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(220, 32, 54, 72), Color.argb(145, 20, 43, 61), Color.argb(195, 12, 25, 39)});
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), Color.argb(105, 210, 242, 255));
        return drawable;
    }

    private GradientDrawable buttonSurface(int solidColor) {
        if (!glassEnabled) return roundRect(solidColor, 18);
        int light = Color.argb(238, Math.min(255, Color.red(solidColor) + 24),
                Math.min(255, Color.green(solidColor) + 24), Math.min(255, Color.blue(solidColor) + 24));
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{light, solidColor});
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), Color.argb(90, 255, 255, 255));
        return drawable;
    }

    private void applyAppearance() {
        if (rootView == null) return;
        if (glassEnabled) {
            GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(5, 13, 25), Color.rgb(11, 35, 50), Color.rgb(14, 18, 38)});
            rootView.setBackground(backdrop);
            cameraCard.setBackground(glassSurface(24));
            controlsCard.setBackground(glassSurface(22));
            appearanceCard.setBackground(glassSurface(22));
            resultPreview.setBackground(glassSurface(22));
        } else {
            rootView.setBackgroundColor(NAVY);
            cameraCard.setBackground(roundRect(PANEL, 24));
            controlsCard.setBackground(roundRect(PANEL, 22));
            appearanceCard.setBackground(roundRect(PANEL, 22));
            resultPreview.setBackground(roundRect(PANEL, 22));
        }
        restyleButtons(controlsCard);
        restyleButtons(resultActions);
    }

    private void restyleButtons(ViewGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && child.getTag() instanceof Integer)
                child.setBackground(buttonSurface((Integer) child.getTag()));
            else if (child instanceof ViewGroup) restyleButtons((ViewGroup) child);
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        recycleDisplayedResults();
        super.onDestroy();
        worker.shutdown();
    }
}
