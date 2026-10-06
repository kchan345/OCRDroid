package io.github.ocrdroid;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class MainActivity extends AppCompatActivity {
    static final int ACCENT = Color.rgb(18, 111, 100);
    private OcrViewModel model;
    private DrawerLayout drawer;
    private Toolbar toolbar;
    private View inputScreen, resultScreen;
    private TextView status, selection, engine;
    private Button settingsShortcut, cancel, recognize, modeToggle;
    private DocumentView preview;
    private SelectionEditor editor;
    private WebView rendered;
    private String renderedText;
    private Bitmap renderedImage;
    private boolean rendering;
    private Uri cameraUri;
    private final List<View> controls = new ArrayList<>();
    private final List<View> compactable = new ArrayList<>();

    private final ActivityResultLauncher<PickVisualMediaRequest> photos =
        registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) model.openImage(uri);
        });
    private final ActivityResultLauncher<Uri> camera = registerForActivityResult(
        new ActivityResultContracts.TakePicture(), success -> {
            if (success && cameraUri != null) model.openImage(cameraUri);
        });
    private final ActivityResultLauncher<String> export = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("text/markdown"), uri -> { if (uri != null) model.saveText(uri); });

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING |
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        model = new ViewModelProvider(this).get(OcrViewModel.class);
        if (saved != null && saved.getString("camera") != null) cameraUri = Uri.parse(saved.getString("camera"));

        drawer = new DrawerLayout(this);
        drawer.setId(R.id.drawer);
        LinearLayout root = Ui.column(this);
        drawer.addView(root, new DrawerLayout.LayoutParams(-1, -1));
        toolbar = Ui.toolbar(this, R.string.app_name, R.drawable.ic_menu, R.string.open_menu,
            () -> drawer.openDrawer(GravityCompat.START));
        root.addView(toolbar);
        LinearLayout body = Ui.column(this);
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            boolean typing = insets.isVisible(WindowInsetsCompat.Type.ime());
            for (View item : compactable) item.setVisibility(typing ? View.GONE : View.VISIBLE);
            return insets;
        });

        status = Ui.label(this, R.string.ready, 13);
        status.setMaxLines(4);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        compactable.add(status);
        FrameLayout screens = new FrameLayout(this);
        body.addView(screens, new LinearLayout.LayoutParams(-1, 0, 1));
        inputScreen = buildInputScreen();
        resultScreen = buildResultScreen();
        screens.addView(inputScreen, new FrameLayout.LayoutParams(-1, -1));
        screens.addView(resultScreen, new FrameLayout.LayoutParams(-1, -1));

        drawer.addView(buildDrawer(), new DrawerLayout.LayoutParams(Ui.dp(this, 280), -1, Gravity.START));
        setContentView(drawer);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else if (model.showingResult) model.backToInput();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
        model.status.observe(this, value -> status.setText(value));
        model.busy.observe(this, working -> {
            for (View control : controls) control.setEnabled(!working);
            cancel.setEnabled(working && model.canCancel());
            if (working) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        model.revision.observe(this, value -> refresh());
    }

    @Override protected void onResume() {
        super.onResume();
        showEngine();
    }

    private View buildInputScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.input_screen);
        LinearLayout column = Ui.column(this);
        scroll.addView(column);
        column.addView(Ui.heading(this, R.string.step_choose));
        column.addView(Ui.label(this, R.string.step_choose_detail, 14));
        engine = Ui.label(this, R.string.ready, 14);
        engine.setId(R.id.engine_summary);
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.rgb(230, 240, 236));
        card.setCornerRadius(Ui.dp(this, 8));
        engine.setBackground(card);
        int pad = Ui.dp(this, 12);
        engine.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        column.addView(engine, cardParams);
        settingsShortcut = Ui.button(this, R.string.open_settings, this::openSettings);
        column.addView(settingsShortcut);
        Button take = control(Ui.button(this, R.string.camera, this::takePhoto));
        take.setId(R.id.camera);
        take.setMinHeight(Ui.dp(this, 72));
        column.addView(take, spaced());
        Button pick = control(Ui.button(this, R.string.photos, () -> photos.launch(new PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build())));
        pick.setId(R.id.photos);
        pick.setMinHeight(Ui.dp(this, 72));
        column.addView(pick, spaced());
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        if (info.totalMem <= 6_000_000_000L) column.addView(Ui.label(this, R.string.low_memory, 12));
        column.addView(Ui.label(this, R.string.device_warning, 12));
        return scroll;
    }

    private View buildResultScreen() {
        LinearLayout column = Ui.column(this);
        column.setId(R.id.result_screen);
        LinearLayout actions = Ui.row(this);
        actions.addView(control(Ui.button(this, R.string.new_image, () -> model.backToInput())), Ui.weighted());
        recognize = control(Ui.button(this, R.string.recognize, () -> model.recognize()));
        recognize.setId(R.id.recognize);
        actions.addView(recognize, Ui.weighted());
        cancel = Ui.button(this, R.string.cancel, () -> model.cancel());
        cancel.setEnabled(false);
        actions.addView(cancel, Ui.weighted());
        column.addView(actions);
        compactable.add(actions);

        LinearLayout panels = Ui.row(this);
        column.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout left = Ui.column(this);
        left.addView(Ui.panelTitle(this, R.string.panel_image));
        preview = new DocumentView(this);
        preview.setId(R.id.preview);
        left.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -1, 1);
        leftParams.setMarginEnd(Ui.dp(this, 6));
        panels.addView(left, leftParams);

        LinearLayout right = Ui.column(this);
        LinearLayout header = Ui.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.panelTitle(this, R.string.panel_output), Ui.weighted());
        modeToggle = control(Ui.button(this, R.string.mode_edit, () -> {
            model.editing = !model.editing;
            refresh();
        }));
        modeToggle.setId(R.id.mode_toggle);
        header.addView(modeToggle);
        right.addView(header);
        FrameLayout output = new FrameLayout(this);
        output.setBackgroundColor(Color.WHITE);
        rendered = new WebView(this);
        rendered.setId(R.id.rendered);
        rendered.setContentDescription(getString(R.string.rendered_description));
        WebSettings web = rendered.getSettings();
        web.setJavaScriptEnabled(false);
        web.setBlockNetworkLoads(true);
        web.setAllowFileAccess(false);
        web.setAllowContentAccess(false);
        web.setBuiltInZoomControls(true);
        web.setDisplayZoomControls(false);
        output.addView(rendered, new FrameLayout.LayoutParams(-1, -1));
        editor = new SelectionEditor(this);
        editor.setId(R.id.editor);
        editor.setSaveEnabled(false);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextSize(14);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHint(R.string.editor_hint);
        controls.add(editor);
        output.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        right.addView(output, new LinearLayout.LayoutParams(-1, 0, 1));
        panels.addView(right, new LinearLayout.LayoutParams(0, -1, 1));

        selection = Ui.label(this, R.string.selection_hint, 12);
        selection.setMaxLines(2);
        column.addView(selection);
        Button save = control(Ui.button(this, R.string.export_text, () -> export.launch("ocr.md")));
        column.addView(save);
        compactable.add(save);
        editor.selectionChanged = this::showSelection;
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!rendering) model.edit(value.toString(), start, before, count);
            }
            @Override public void afterTextChanged(Editable value) {
                if (!rendering) showSelection(editor.getSelectionStart(), editor.getSelectionEnd());
            }
        });
        return column;
    }

    private View buildDrawer() {
        LinearLayout panel = Ui.column(this);
        panel.setId(R.id.drawer_menu);
        panel.setBackgroundColor(Color.WHITE);
        panel.setClickable(true);
        ViewCompat.setOnApplyWindowInsetsListener(panel, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(Ui.dp(this, 8) + bars.left, Ui.dp(this, 16) + bars.top, Ui.dp(this, 8), bars.bottom);
            return insets;
        });
        TextView title = Ui.heading(this, R.string.app_name);
        title.setPadding(Ui.dp(this, 12), 0, 0, Ui.dp(this, 12));
        panel.addView(title);
        panel.addView(Ui.menuItem(this, R.id.nav_scan, R.string.nav_scan, () -> {
            drawer.closeDrawer(GravityCompat.START);
            if (model.showingResult && !Boolean.TRUE.equals(model.busy.getValue())) model.backToInput();
        }));
        panel.addView(Ui.menuItem(this, R.id.nav_settings, R.string.nav_settings, () -> {
            drawer.closeDrawer(GravityCompat.START);
            openSettings();
        }));
        TextView version = Ui.label(this, 0, 12);
        version.setText(getString(R.string.version, BuildConfig.VERSION_NAME));
        version.setPadding(Ui.dp(this, 12), Ui.dp(this, 16), 0, 0);
        panel.addView(version);
        return panel;
    }

    private void openSettings() { startActivity(new Intent(this, SettingsActivity.class)); }

    private void showEngine() {
        String summary = model.engineSummary();
        engine.setText(summary);
        toolbar.setSubtitle(summary.split("\n", 2)[0]);
    }

    private void refresh() {
        rendering = true;
        inputScreen.setVisibility(model.showingResult ? View.GONE : View.VISIBLE);
        resultScreen.setVisibility(model.showingResult ? View.VISIBLE : View.GONE);
        if (!editor.getText().toString().equals(model.text)) editor.setText(model.text);
        preview.setImage(model.image);
        editor.setVisibility(model.editing ? View.VISIBLE : View.GONE);
        rendered.setVisibility(model.editing ? View.GONE : View.VISIBLE);
        selection.setVisibility(model.editing ? View.VISIBLE : View.GONE);
        modeToggle.setText(model.editing ? R.string.mode_rendered : R.string.mode_edit);
        if (!model.editing && (!model.text.equals(renderedText) || model.image != renderedImage)) {
            renderedText = model.text;
            renderedImage = model.image;
            String markdown = model.text.isEmpty() ? getString(R.string.output_placeholder) : model.text;
            rendered.loadDataWithBaseURL(null, Markdown.document(markdown, this::figure), "text/html", "utf-8", null);
        }
        rendering = false;
        showEngine();
        if (model.editing) showSelection(editor.getSelectionStart(), editor.getSelectionEnd());
        else preview.highlight(java.util.Collections.emptyList());
    }

    /** Crops a figure region reported by OvisOCR2 in [0, 1000) coordinates for the rendered view. */
    private String figure(int left, int top, int right, int bottom) {
        Bitmap page = model.image;
        if (page == null || right <= left || bottom <= top) return null;
        int x0 = Math.max(0, Math.min(page.getWidth() - 1, left * page.getWidth() / 1000));
        int y0 = Math.max(0, Math.min(page.getHeight() - 1, top * page.getHeight() / 1000));
        int x1 = Math.max(x0 + 1, Math.min(page.getWidth(), right * page.getWidth() / 1000));
        int y1 = Math.max(y0 + 1, Math.min(page.getHeight(), bottom * page.getHeight() / 1000));
        Bitmap crop = Bitmap.createBitmap(page, x0, y0, x1 - x0, y1 - y0);
        float scale = Math.min(1f, 640f / Math.max(crop.getWidth(), crop.getHeight()));
        Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(crop, Math.max(1, Math.round(crop.getWidth() * scale)),
            Math.max(1, Math.round(crop.getHeight() * scale)), true) : crop;
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
        if (small != crop) small.recycle();
        if (crop != page) crop.recycle();
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg.toByteArray());
    }

    private void showSelection(int start, int end) {
        if (preview == null || selection == null || !model.editing) return;
        List<TextAnchors.Box> boxes = TextAnchors.selected(model.anchors, start, end);
        preview.highlight(boxes);
        if (start == end) selection.setText(R.string.selection_hint);
        else if (boxes.isEmpty()) selection.setText(R.string.selection_unmatched);
        else selection.setText(getString(R.string.selection_matched, boxes.size()));
    }

    private void takePhoto() {
        try {
            File directory = new File(getCacheDir(), "camera");
            if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("Cannot create camera directory");
            File output = new File(directory, "capture.jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", output);
            camera.launch(cameraUri);
        } catch (ActivityNotFoundException error) {
            model.status.setValue(getString(R.string.no_camera));
        } catch (IOException | IllegalArgumentException error) {
            model.status.setValue(getString(R.string.failure, error.getMessage()));
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraUri != null) out.putString("camera", cameraUri.toString());
    }

    @Override protected void onDestroy() {
        rendered.destroy();
        super.onDestroy();
    }

    private <T extends View> T control(T view) { controls.add(view); return view; }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, Ui.dp(this, 8), 0, 0);
        return params;
    }
}
