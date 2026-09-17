package com.lumastack.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int TAKE_PHOTO = 41, PICK_PHOTOS = 42;
    private final List<Uri> photos = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private NumberPicker countPicker;
    private TextView status;
    private Button captureButton, importButton, stackButton;
    private ProgressBar progress;
    private ImageView preview;
    private Uri pendingCapture;
    private File pendingFile;
    private int targetCount;
    private boolean capturing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16, 23, 34));
        getWindow().setNavigationBarColor(Color.rgb(16, 23, 34));
        buildUi();
        if (state != null) {
            ArrayList<String> saved = state.getStringArrayList("photos");
            if (saved != null) for (String s : saved) photos.add(Uri.parse(s));
            targetCount = state.getInt("targetCount", 0);
            capturing = state.getBoolean("capturing", false);
            String uri = state.getString("pendingCapture");
            String path = state.getString("pendingFile");
            if (uri != null) pendingCapture = Uri.parse(uri);
            if (path != null) pendingFile = new File(path);
            if (targetCount >= 2) countPicker.setValue(targetCount);
        }
        refresh();
    }

    private void buildUi() {
        int navy = Color.rgb(16, 23, 34), ink = Color.rgb(232, 239, 248), muted = Color.rgb(167, 181, 199);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(navy);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(32), dp(24), dp(28)); scroll.addView(page); setContentView(scroll);
        TextView title = label("LumaStack", 32, ink); title.setTypeface(null, 1); page.addView(title);
        TextView subtitle = label("One polished photo from a series of shots.", 16, muted);
        subtitle.setPadding(0, dp(6), 0, dp(24)); page.addView(subtitle);
        TextView countTitle = label("Number of photos", 18, ink); page.addView(countTitle);
        countPicker = new NumberPicker(this); countPicker.setMinValue(2); countPicker.setMaxValue(12);
        countPicker.setValue(3); countPicker.setWrapSelectorWheel(false);
        countPicker.setOnValueChangedListener((picker, oldValue, newValue) -> refreshButtons());
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(-1, dp(132));
        page.addView(countPicker, pickerParams);
        captureButton = button("Take photo series", () -> startSeries()); page.addView(captureButton);
        importButton = button("Choose existing photos", () -> choosePhotos()); page.addView(importButton);
        stackButton = button("Stack and save", () -> stack()); page.addView(stackButton);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(8)); bar.topMargin = dp(20);
        page.addView(progress, bar);
        status = label("Choose a count, then capture or import photos.", 15, muted);
        status.setPadding(0, dp(18), 0, dp(18)); page.addView(status);
        preview = new ImageView(this); preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.addView(preview, new LinearLayout.LayoutParams(-1, dp(300)));
        TextView note = label("Processing stays on your device. Best results: hold still and use shots of the same scene with different exposures. Output is up to 2048 px on its longest side.", 14, muted);
        note.setPadding(0, dp(22), 0, 0); page.addView(note);
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(sp);
        view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setTextSize(16); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54)); p.topMargin = dp(12);
        button.setLayoutParams(p); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void startSeries() {
        photos.clear(); targetCount = countPicker.getValue(); capturing = true; refresh(); launchCamera();
    }
    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "captures");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create capture folder.");
            pendingFile = File.createTempFile("frame_", ".jpg", dir);
            pendingCapture = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCapture);
            intent.setClipData(ClipData.newRawUri("capture", pendingCapture));
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, TAKE_PHOTO);
        } catch (Exception e) {
            capturing = false; status.setText("Camera unavailable: " + e.getMessage()); refreshButtons();
        }
    }
    private void choosePhotos() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, PICK_PHOTOS);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == TAKE_PHOTO) {
            if (result == RESULT_OK && pendingFile != null && pendingFile.length() > 0) {
                photos.add(pendingCapture);
                if (photos.size() < targetCount) { refresh(); launchCamera(); return; }
                capturing = false; status.setText("Captured " + photos.size() + " photos. Tap Stack and save.");
            } else {
                capturing = false;
                if (pendingFile != null) pendingFile.delete();
                status.setText("Capture stopped. " + photos.size() + " photos available.");
            }
            refreshButtons();
        } else if (request == PICK_PHOTOS && result == RESULT_OK && data != null) {
            photos.clear(); capturing = false;
            if (data.getClipData() != null) {
                int count = Math.min(12, data.getClipData().getItemCount());
                for (int i = 0; i < count; i++) photos.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) photos.add(data.getData());
            for (Uri uri : photos) try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            targetCount = countPicker.getValue();
            status.setText(photos.size() == targetCount ? "Ready to stack " + photos.size() + " photos."
                    : "Selected " + photos.size() + " photos; choose exactly " + targetCount + ".");
            refreshButtons();
        }
    }
    private void refresh() {
        if (capturing) status.setText("Photo " + (photos.size() + 1) + " of " + targetCount + ": capture the same scene.");
        refreshButtons();
    }
    private void refreshButtons() {
        captureButton.setEnabled(!capturing); importButton.setEnabled(!capturing);
        stackButton.setEnabled(!capturing && photos.size() == countPicker.getValue());
    }

    private void stack() {
        if (photos.size() != countPicker.getValue()) {
            status.setText("Choose exactly " + countPicker.getValue() + " photos."); return;
        }
        List<Uri> selection = new ArrayList<>(photos);
        captureButton.setEnabled(false); importButton.setEnabled(false); stackButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE); progress.setProgress(0);
        status.setText("Aligning and blending on this device…");
        worker.execute(() -> {
            Bitmap result = null;
            try {
                result = StackProcessor.process(this, selection, (done, total) -> runOnUiThread(() -> {
                    progress.setProgress(Math.round(done * 90f / total));
                    status.setText("Blending photo " + done + " of " + total + "…");
                }));
                Uri saved = save(result);
                Bitmap display = result;
                runOnUiThread(() -> {
                    preview.setImageBitmap(display);
                    progress.setProgress(100); progress.setVisibility(View.GONE);
                    status.setText("Saved to Pictures/LumaStack. " + saved);
                    refreshButtons();
                });
            } catch (Exception e) {
                if (result != null) result.recycle();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE); status.setText("Could not stack: " + e.getMessage());
                    refreshButtons();
                });
            }
        });
    }

    private Uri save(Bitmap bitmap) throws IOException {
        String name = "LumaStack_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LumaStack");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
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

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> saved = new ArrayList<>(); for (Uri uri : photos) saved.add(uri.toString());
        out.putStringArrayList("photos", saved); out.putInt("targetCount", targetCount);
        out.putBoolean("capturing", capturing);
        if (pendingCapture != null) out.putString("pendingCapture", pendingCapture.toString());
        if (pendingFile != null) out.putString("pendingFile", pendingFile.getAbsolutePath());
    }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdown(); }
}
