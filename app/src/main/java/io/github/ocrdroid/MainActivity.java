package io.github.ocrdroid;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Scan workflow: 1) choose an image, 2) adjust it, 3) image and OCR output side by side. */
public final class MainActivity extends AppCompatActivity {
    private static final int PRIMARY = androidx.appcompat.R.attr.colorPrimary;
    private static final int ON_SURFACE = com.google.android.material.R.attr.colorOnSurface;
    private static final int PRIMARY_CONTAINER = com.google.android.material.R.attr.colorPrimaryContainer;
    private static final int ON_PRIMARY_CONTAINER = com.google.android.material.R.attr.colorOnPrimaryContainer;
    private OcrViewModel model;
    private DrawerLayout drawer;
    private MaterialToolbar toolbar;
    private View inputScreen, adjustScreen, resultScreen;
    private TextView status, selection, engine, cropSize, straightenValue, thresholdValue, keepDetail;
    private ImageView engineIcon;
    private MaterialButton cancel, thresholdAuto;
    private MaterialButtonToggleGroup modeGroup, keepGroup;
    private CropView cropView;
    private Slider straighten, threshold;
    private MaterialSwitch grayscale, blackWhite;
    private LinearProgressIndicator progress;
    private DocumentView preview;
    private SelectionEditor editor;
    private WebView rendered;
    private String renderedText;
    private Bitmap renderedImage;
    private boolean rendering, updating;
    private Uri cameraUri;
    private final List<View> controls = new ArrayList<>();
    private View resultActions, saveButton, adjustTools, adjustBar, imageCard, textCard;
    private MaterialButton expandCrop, expandImage, expandText;
    private boolean typing;
    private int focusedStart = -1, focusedEnd = -1;

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
        progress = new LinearProgressIndicator(this);
        progress.setId(R.id.progress);
        progress.setIndeterminate(true);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            boolean ime = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (ime != typing) {
                typing = ime;
                view.post(this::applyChrome);
            }
            return insets;
        });

        status = Ui.label(this, R.string.ready, 13);
        status.setId(R.id.status);
        status.setMaxLines(3);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        FrameLayout screens = new FrameLayout(this);
        root.addView(screens, new LinearLayout.LayoutParams(-1, 0, 1));
        inputScreen = buildInputScreen();
        adjustScreen = buildAdjustScreen();
        resultScreen = buildResultScreen();
        screens.addView(inputScreen, new FrameLayout.LayoutParams(-1, -1));
        screens.addView(adjustScreen, new FrameLayout.LayoutParams(-1, -1));
        screens.addView(resultScreen, new FrameLayout.LayoutParams(-1, -1));

        drawer.addView(buildDrawer(), new DrawerLayout.LayoutParams(-2, -1, Gravity.START));
        setContentView(drawer);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else if (model.expanded != OcrViewModel.Expanded.NONE) expand(OcrViewModel.Expanded.NONE);
                else if (model.step == OcrViewModel.Step.RESULT && model.source != null) model.adjust();
                else if (model.step != OcrViewModel.Step.INPUT) model.backToInput();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
        model.status.observe(this, value -> status.setText(value));
        model.busy.observe(this, working -> {
            for (View control : controls) control.setEnabled(!working);
            cancel.setEnabled(working && model.canCancel());
            progress.setVisibility(working ? View.VISIBLE : View.INVISIBLE);
            if (working) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (model.step == OcrViewModel.Step.ADJUST) syncAdjust();
        });
        model.revision.observe(this, value -> refresh());
    }

    @Override protected void onResume() {
        super.onResume();
        showEngine();
    }

    private boolean idle() { return !Boolean.TRUE.equals(model.busy.getValue()); }

    // Step 1: choose an image.
    private View buildInputScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.input_screen);
        LinearLayout column = Ui.column(this);
        int pad = Ui.dp(this, 16);
        column.setPadding(pad, Ui.dp(this, 4), pad, pad);
        scroll.addView(column);
        column.addView(Ui.headline(this, R.string.step_choose));
        column.addView(Ui.label(this, R.string.step_choose_detail, 14));

        LinearLayout sources = Ui.row(this);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.setMarginEnd(Ui.dp(this, 6));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(Ui.dp(this, 6));
        sources.addView(sourceCard(R.id.camera, R.drawable.ic_camera, R.string.camera, R.string.camera_detail,
            this::takePhoto), first);
        sources.addView(sourceCard(R.id.photos, R.drawable.ic_photos, R.string.photos, R.string.photos_detail,
            this::pickPhoto), second);
        column.addView(sources, Ui.margins(this, -1, -2, 16, 12));

        MaterialCardView engineCard = Ui.card(this);
        LinearLayout content = Ui.content(engineCard);
        LinearLayout header = Ui.row(this);
        engineIcon = icon(R.drawable.ic_memory, PRIMARY);
        header.addView(engineIcon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView title = Ui.text(this, R.string.engine_card_title,
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, ON_SURFACE);
        title.setPaddingRelative(Ui.dp(this, 12), 0, 0, 0);
        header.addView(title, Ui.weighted());
        header.addView(control(Ui.textButton(this, R.string.open_settings, R.drawable.ic_tune, this::openSettings)));
        content.addView(header);
        engine = Ui.label(this, R.string.ready, 14);
        engine.setId(R.id.engine_summary);
        content.addView(engine);
        column.addView(engineCard, Ui.margins(this, -1, -2, 0, 12));

        MaterialCardView steps = Ui.card(this);
        Ui.content(steps).addView(Ui.text(this, R.string.steps_title,
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall, ON_SURFACE));
        Ui.content(steps).addView(Ui.label(this, R.string.steps_detail, 14));
        column.addView(steps, Ui.margins(this, -1, -2, 0, 12));

        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        if (info.totalMem <= 6_000_000_000L) column.addView(Ui.label(this, R.string.low_memory, 12));
        column.addView(Ui.label(this, R.string.device_warning, 12));
        return scroll;
    }

    private MaterialCardView sourceCard(int id, int iconResource, int title, int detail, Runnable action) {
        MaterialCardView card = Ui.card(this);
        card.setId(id);
        card.setCardBackgroundColor(Ui.color(this, PRIMARY_CONTAINER));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(getString(title) + ". " + getString(detail));
        card.setOnClickListener(view -> action.run());
        LinearLayout content = Ui.content(card);
        int pad = Ui.dp(this, 20);
        content.setPadding(pad, pad, pad, pad);
        content.addView(icon(iconResource, ON_PRIMARY_CONTAINER), new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView name = Ui.text(this, title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
            ON_PRIMARY_CONTAINER);
        name.setPadding(0, Ui.dp(this, 14), 0, 0);
        content.addView(name);
        content.addView(Ui.text(this, detail, com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
            ON_PRIMARY_CONTAINER));
        controls.add(card);
        return card;
    }

    private ImageView icon(int drawable, int colorAttribute) {
        ImageView view = new ImageView(this);
        view.setImageResource(drawable);
        view.setImageTintList(ColorStateList.valueOf(Ui.color(this, colorAttribute)));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    // Step 2: crop, rotate, and filter with a live preview.
    private View buildAdjustScreen() {
        LinearLayout column = Ui.column(this);
        column.setId(R.id.adjust_screen);
        cropView = new CropView(this);
        cropView.setId(R.id.crop_view);
        cropView.setAccent(Ui.color(this, PRIMARY));
        cropView.setOnCropChanged(this::showCropSize);
        controls.add(cropView);
        column.addView(cropView, new LinearLayout.LayoutParams(-1, 0, 1.2f));
        LinearLayout sizeRow = Ui.row(this);
        sizeRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        cropSize = Ui.label(this, 0, 12);
        cropSize.setId(R.id.crop_size);
        sizeRow.addView(cropSize, Ui.weighted());
        expandCrop = expandButton(R.id.expand_crop, OcrViewModel.Expanded.IMAGE);
        sizeRow.addView(expandCrop);
        column.addView(sizeRow, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.adjust_tools);
        adjustTools = scroll;
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout tools = Ui.column(this);
        int pad = Ui.dp(this, 16);
        tools.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 8));
        scroll.addView(tools);

        LinearLayout rotation = Ui.row(this);
        rotation.addView(tool(R.id.rotate_left, R.drawable.ic_rotate_left, R.string.rotate_left, () -> model.edits.rotate(-1)));
        rotation.addView(tool(R.id.rotate_right, R.drawable.ic_rotate_right, R.string.rotate_right, () -> model.edits.rotate(1)));
        rotation.addView(tool(R.id.crop_reset, R.drawable.ic_fullscreen, R.string.crop_full, () -> model.edits.resetCrop()));
        rotation.addView(new View(this), Ui.weighted());
        MaterialButton reset = control(Ui.textButton(this, R.string.reset_edits, 0, () -> {
            model.edits = new Edits();
            syncAdjust();
        }));
        reset.setId(R.id.edits_reset);
        rotation.addView(reset);
        tools.addView(rotation);

        straightenValue = Ui.label(this, 0, 13);
        tools.addView(sliderHeader(R.string.straighten, straightenValue));
        straighten = slider(R.id.straighten, -Edits.MAX_STRAIGHTEN, Edits.MAX_STRAIGHTEN, 0.5f, R.string.straighten);
        straighten.addOnChangeListener((view, value, user) -> {
            if (updating) return;
            model.edits.setStraighten(value);
            syncAdjust();
        });
        tools.addView(straighten);

        grayscale = switchView(tools, R.id.grayscale, R.string.grayscale);
        grayscale.setOnCheckedChangeListener((view, checked) -> {
            if (updating) return;
            model.edits.grayscale = checked;
            syncAdjust();
        });
        blackWhite = switchView(tools, R.id.black_white, R.string.black_white);
        blackWhite.setOnCheckedChangeListener((view, checked) -> {
            if (updating) return;
            model.edits.blackWhite = checked;
            syncAdjust();
        });
        thresholdValue = Ui.label(this, 0, 13);
        tools.addView(sliderHeader(R.string.threshold, thresholdValue));
        LinearLayout thresholdRow = Ui.row(this);
        threshold = slider(R.id.threshold, 1, 254, 1, R.string.threshold);
        threshold.addOnChangeListener((view, value, user) -> {
            if (updating) return;
            model.edits.setThreshold(Math.round(value));
            syncAdjust();
        });
        thresholdRow.addView(threshold, new LinearLayout.LayoutParams(0, -2, 1));
        thresholdAuto = control(Ui.tonal(this, R.string.threshold_auto, R.drawable.ic_auto, () -> {
            model.edits.setThreshold(model.autoThreshold());
            syncAdjust();
        }));
        thresholdAuto.setId(R.id.threshold_auto);
        thresholdAuto.setTooltipText(getString(R.string.threshold_auto_description));
        thresholdRow.addView(thresholdAuto);
        tools.addView(thresholdRow);

        TextView keepTitle = Ui.panelTitle(this, R.string.keep_title);
        keepTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        tools.addView(keepTitle);
        keepGroup = new MaterialButtonToggleGroup(this);
        keepGroup.setId(R.id.keep_group);
        keepGroup.setSingleSelection(true);
        keepGroup.setSelectionRequired(true);
        keepGroup.addView(control(Ui.segment(this, R.id.keep_original, R.string.keep_original, 0)),
            new LinearLayout.LayoutParams(0, -2, 1));
        keepGroup.addView(control(Ui.segment(this, R.id.keep_region, R.string.keep_region, 0)),
            new LinearLayout.LayoutParams(0, -2, 1));
        keepGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (updating || !checked) return;
            model.setKeepOriginal(id == R.id.keep_original);
            syncAdjust();
        });
        tools.addView(keepGroup, new LinearLayout.LayoutParams(-1, -2));
        keepDetail = Ui.label(this, 0, 12);
        tools.addView(keepDetail);

        LinearLayout bar = Ui.row(this);
        bar.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
        bar.setBackgroundColor(Ui.color(this, com.google.android.material.R.attr.colorSurfaceContainer));
        MaterialButton back = control(Ui.outlined(this, R.string.back, R.drawable.ic_back, () -> model.backToInput()));
        back.setId(R.id.adjust_back);
        bar.addView(back, Ui.weighted());
        MaterialButton apply = control(Ui.filled(this, R.string.recognize, R.drawable.ic_play, () -> model.applyEdits()));
        apply.setId(R.id.apply_edits);
        LinearLayout.LayoutParams applyParams = Ui.weighted();
        applyParams.setMarginStart(Ui.dp(this, 12));
        bar.addView(apply, applyParams);
        bar.setId(R.id.adjust_bar);
        adjustBar = bar;
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return column;
    }

    private MaterialButton tool(int id, int drawable, int description, Runnable change) {
        MaterialButton button = control(Ui.iconButton(this, drawable, description, () -> {
            change.run();
            syncAdjust();
        }));
        button.setId(id);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginEnd(Ui.dp(this, 4));
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout sliderHeader(int title, TextView value) {
        LinearLayout header = Ui.row(this);
        header.setPadding(0, Ui.dp(this, 8), 0, 0);
        header.addView(Ui.panelTitle(this, title), Ui.weighted());
        header.addView(value);
        return header;
    }

    private Slider slider(int id, float from, float to, float step, int description) {
        Slider slider = new Slider(this);
        slider.setId(id);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(step);
        slider.setValue(from <= 0 && to >= 0 ? 0 : Math.round((from + to) / 2));
        slider.setContentDescription(getString(description));
        controls.add(slider);
        return slider;
    }

    private MaterialSwitch switchView(LinearLayout parent, int id, int text) {
        MaterialSwitch view = new MaterialSwitch(this);
        view.setId(id);
        view.setText(text);
        view.setMinHeight(Ui.dp(this, 48));
        controls.add(view);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    /** Copies the edit model into the adjust controls and redraws the live preview. */
    private void syncAdjust() {
        updating = true;
        Edits edits = model.edits;
        boolean idle = idle();
        cropView.show(model.sourcePreview, edits);
        straighten.setValue(Math.round(edits.straighten * 2f) / 2f);
        straightenValue.setText(getString(R.string.degrees, edits.straighten));
        grayscale.setChecked(edits.grayscale || edits.blackWhite);
        grayscale.setEnabled(idle && !edits.blackWhite);
        blackWhite.setChecked(edits.blackWhite);
        threshold.setValue(edits.threshold);
        threshold.setEnabled(idle && edits.blackWhite);
        thresholdAuto.setEnabled(idle && edits.blackWhite);
        thresholdValue.setText(String.valueOf(edits.threshold));
        keepGroup.check(model.keepOriginal ? R.id.keep_original : R.id.keep_region);
        keepDetail.setText(model.keepOriginal ? R.string.keep_original_detail : R.string.keep_region_detail);
        showCropSize();
        updating = false;
    }

    private void showCropSize() {
        Bitmap source = model.source;
        if (source == null) { cropSize.setText(""); return; }
        int[] frame = model.edits.frameSize(source.getWidth(), source.getHeight(), PageEditor.MAX_EDGE);
        int[] crop = model.edits.cropPixels(frame[0], frame[1]);
        cropSize.setText(getString(R.string.crop_size, crop[2] - crop[0], crop[3] - crop[1]));
    }

    // Step 3: image and OCR output side by side.
    private View buildResultScreen() {
        LinearLayout column = Ui.column(this);
        column.setId(R.id.result_screen);
        int pad = Ui.dp(this, 12);
        column.setPadding(pad, 0, pad, Ui.dp(this, 8));
        LinearLayout actions = Ui.row(this);
        MaterialButton newImage = control(Ui.iconButton(this, R.drawable.ic_add_photo, R.string.new_image, () -> model.backToInput()));
        newImage.setId(R.id.new_image);
        actions.addView(newImage);
        MaterialButton adjust = control(Ui.iconButton(this, R.drawable.ic_crop, R.string.adjust, () -> model.adjust()));
        adjust.setId(R.id.adjust);
        LinearLayout.LayoutParams adjustParams = new LinearLayout.LayoutParams(-2, -2);
        adjustParams.setMarginStart(Ui.dp(this, 4));
        actions.addView(adjust, adjustParams);
        MaterialButton recognize = control(Ui.filled(this, R.string.recognize, R.drawable.ic_play, () -> model.recognize()));
        recognize.setId(R.id.recognize);
        LinearLayout.LayoutParams runParams = Ui.weighted();
        runParams.setMarginStart(Ui.dp(this, 8));
        runParams.setMarginEnd(Ui.dp(this, 4));
        actions.addView(recognize, runParams);
        cancel = Ui.iconButton(this, R.drawable.ic_stop, R.string.cancel, () -> model.cancel());
        cancel.setId(R.id.cancel);
        cancel.setEnabled(false);
        actions.addView(cancel);
        column.addView(actions);
        resultActions = actions;

        LinearLayout panels = Ui.row(this);
        panels.setGravity(Gravity.NO_GRAVITY);
        column.addView(panels, Ui.margins(this, -1, 0, 4, 4));
        ((LinearLayout.LayoutParams) panels.getLayoutParams()).weight = 1;

        MaterialCardView leftCard = panelCard();
        LinearLayout left = Ui.content(leftCard);
        imageCard = leftCard;
        leftCard.setId(R.id.image_card);
        LinearLayout imageHeader = Ui.row(this);
        imageHeader.addView(Ui.panelTitle(this, R.string.panel_image), Ui.weighted());
        expandImage = expandButton(R.id.expand_image, OcrViewModel.Expanded.IMAGE);
        imageHeader.addView(expandImage);
        left.addView(imageHeader, panelTitleParams());
        preview = new DocumentView(this);
        preview.setId(R.id.preview);
        preview.setAccent(Ui.color(this, PRIMARY));
        left.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -1, 1);
        leftParams.setMarginEnd(Ui.dp(this, 6));
        panels.addView(leftCard, leftParams);

        MaterialCardView rightCard = panelCard();
        LinearLayout right = Ui.content(rightCard);
        LinearLayout header = Ui.row(this);
        header.addView(Ui.panelTitle(this, R.string.panel_output), Ui.weighted());
        modeGroup = new MaterialButtonToggleGroup(this);
        modeGroup.setId(R.id.mode_group);
        modeGroup.setSingleSelection(true);
        modeGroup.setSelectionRequired(true);
        modeGroup.addView(control(modeButton(R.id.mode_rendered, R.drawable.ic_preview, R.string.mode_rendered)));
        modeGroup.addView(control(modeButton(R.id.mode_edit, R.drawable.ic_edit, R.string.mode_edit)));
        modeGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (rendering || !checked) return;
            model.editing = id == R.id.mode_edit;
            refresh();
        });
        header.addView(modeGroup);
        expandText = expandButton(R.id.expand_text, OcrViewModel.Expanded.TEXT);
        LinearLayout.LayoutParams expandTextParams = new LinearLayout.LayoutParams(-2, -2);
        expandTextParams.setMarginStart(Ui.dp(this, 4));
        header.addView(expandText, expandTextParams);
        textCard = rightCard;
        rightCard.setId(R.id.text_card);
        right.addView(header, panelTitleParams());
        FrameLayout output = new FrameLayout(this);
        rendered = new WebView(this);
        rendered.setId(R.id.rendered);
        rendered.setBackgroundColor(Color.TRANSPARENT);
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
        editor.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        editor.setTextColor(Ui.color(this, ON_SURFACE));
        editor.setBackground(null);
        editor.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHint(R.string.editor_hint);
        controls.add(editor);
        output.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        right.addView(output, new LinearLayout.LayoutParams(-1, 0, 1));
        panels.addView(rightCard, new LinearLayout.LayoutParams(0, -1, 1));

        selection = Ui.label(this, R.string.selection_hint, 12);
        selection.setMaxLines(2);
        column.addView(selection);
        MaterialButton save = control(Ui.tonal(this, R.string.export_text, R.drawable.ic_save, () -> export.launch("ocr.md")));
        save.setId(R.id.save_markdown);
        column.addView(save, new LinearLayout.LayoutParams(-1, -2));
        saveButton = save;
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

    private MaterialCardView panelCard() {
        MaterialCardView card = Ui.card(this);
        card.setRadius(Ui.dp(this, 16));
        LinearLayout content = Ui.content(card);
        content.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        int pad = Ui.dp(this, 8);
        content.setPadding(pad, Ui.dp(this, 4), pad, pad);
        return card;
    }

    private LinearLayout.LayoutParams panelTitleParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        params.setMarginStart(Ui.dp(this, 4));
        return params;
    }

    private MaterialButton modeButton(int id, int drawable, int description) {
        MaterialButton button = Ui.segment(this, id, 0, drawable);
        button.setContentDescription(getString(description));
        button.setTooltipText(getString(description));
        button.setIconPadding(0);
        button.setMinWidth(Ui.dp(this, 44));
        button.setMinimumWidth(Ui.dp(this, 44));
        button.setPaddingRelative(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        return button;
    }

    private View buildDrawer() {
        NavigationView navigation = new NavigationView(this);
        navigation.setId(R.id.drawer_menu);
        LinearLayout header = Ui.column(this);
        int pad = Ui.dp(this, 24);
        header.setPadding(pad, pad, pad, Ui.dp(this, 12));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView name = Ui.text(this, R.string.app_name, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
            ON_SURFACE);
        name.setPadding(0, Ui.dp(this, 12), 0, 0);
        header.addView(name);
        header.addView(Ui.label(this, R.string.tagline, 13));
        TextView version = Ui.label(this, 0, 12);
        version.setText(getString(R.string.version, BuildConfig.VERSION_NAME));
        header.addView(version);
        navigation.addHeaderView(header);
        Menu menu = navigation.getMenu();
        menu.add(Menu.NONE, R.id.nav_scan, 0, R.string.nav_scan).setIcon(R.drawable.ic_scan).setCheckable(true).setChecked(true);
        menu.add(Menu.NONE, R.id.nav_settings, 1, R.string.nav_settings).setIcon(R.drawable.ic_tune);
        navigation.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawer(GravityCompat.START);
            if (item.getItemId() == R.id.nav_settings) {
                openSettings();
                return false;
            }
            if (model.step != OcrViewModel.Step.INPUT && idle()) model.backToInput();
            return true;
        });
        return navigation;
    }

    private void openSettings() { startActivity(new Intent(this, SettingsActivity.class)); }

    private void showEngine() {
        String summary = model.engineSummary();
        engine.setText(summary);
        engineIcon.setImageResource(new AppSettings(this).backend().local() ? R.drawable.ic_memory : R.drawable.ic_cloud);
        toolbar.setSubtitle(model.step == OcrViewModel.Step.INPUT ? null : summary.split("\n", 2)[0]);
    }

    private void refresh() {
        rendering = true;
        OcrViewModel.Step step = model.step;
        Ui.gone(inputScreen, step != OcrViewModel.Step.INPUT);
        Ui.gone(adjustScreen, step != OcrViewModel.Step.ADJUST);
        Ui.gone(resultScreen, step != OcrViewModel.Step.RESULT);
        toolbar.setTitle(step == OcrViewModel.Step.ADJUST ? R.string.adjust_title
            : step == OcrViewModel.Step.RESULT ? R.string.result_title : R.string.app_name);
        if (step == OcrViewModel.Step.ADJUST) syncAdjust();
        if (!editor.getText().toString().equals(model.text)) editor.setText(model.text);
        preview.setImage(model.original != null ? model.original : model.image,
            model.original != null ? model.region : null);
        editor.setVisibility(model.editing ? View.VISIBLE : View.GONE);
        rendered.setVisibility(model.editing ? View.GONE : View.VISIBLE);
        modeGroup.check(model.editing ? R.id.mode_edit : R.id.mode_rendered);
        if (!model.editing && (!model.text.equals(renderedText) || model.image != renderedImage)) {
            renderedText = model.text;
            renderedImage = model.image;
            String markdown = model.text.isEmpty() ? getString(R.string.output_placeholder) : model.text;
            rendered.loadDataWithBaseURL(null, Markdown.document(markdown, this::figure), "text/html", "utf-8", null);
        }
        rendering = false;
        applyChrome();
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

    /** Small icon button that toggles one panel between normal layout and full screen. */
    private MaterialButton expandButton(int id, OcrViewModel.Expanded panel) {
        MaterialButton button = Ui.iconButton(this, R.drawable.ic_open_full, R.string.fullscreen_enter,
            () -> expand(model.expanded == panel ? OcrViewModel.Expanded.NONE : panel));
        button.setId(id);
        button.setMinWidth(Ui.dp(this, 40));
        button.setMinimumWidth(Ui.dp(this, 40));
        button.setMinHeight(Ui.dp(this, 40));
        button.setMinimumHeight(Ui.dp(this, 40));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private void expand(OcrViewModel.Expanded panel) {
        model.expanded = panel;
        applyChrome();
    }

    /** Shows or hides surrounding UI for full-screen panels and while the keyboard is open. */
    private void applyChrome() {
        OcrViewModel.Expanded expanded = model.expanded;
        OcrViewModel.Step step = model.step;
        boolean full = expanded != OcrViewModel.Expanded.NONE && step != OcrViewModel.Step.INPUT;
        Ui.gone(toolbar, full);
        Ui.gone(status, full || typing);
        Ui.gone(resultActions, full || typing);
        Ui.gone(saveButton, full || typing);
        Ui.gone(adjustTools, full);
        Ui.gone(adjustBar, full);
        Ui.gone(cropSize, full);
        Ui.gone(imageCard, full && expanded == OcrViewModel.Expanded.TEXT);
        Ui.gone(textCard, full && expanded == OcrViewModel.Expanded.IMAGE);
        Ui.gone(selection, !model.editing || (full && expanded == OcrViewModel.Expanded.IMAGE));
        LinearLayout.LayoutParams imageParams = (LinearLayout.LayoutParams) imageCard.getLayoutParams();
        imageParams.setMarginEnd(full ? 0 : Ui.dp(this, 6));
        imageCard.setLayoutParams(imageParams);
        for (MaterialButton button : new MaterialButton[] {expandCrop, expandImage, expandText}) {
            String label = getString(full ? R.string.fullscreen_exit : R.string.fullscreen_enter);
            button.setIconResource(full ? R.drawable.ic_close_full : R.drawable.ic_open_full);
            button.setContentDescription(label);
            button.setTooltipText(label);
        }
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (full) {
            bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void showSelection(int start, int end) {
        if (preview == null || selection == null || !model.editing) return;
        List<TextAnchors.Box> boxes = TextAnchors.selected(model.anchors, start, end);
        preview.highlight(boxes);
        if (boxes.isEmpty()) {
            focusedStart = focusedEnd = -1;
        } else if (start != focusedStart || end != focusedEnd) {
            focusedStart = start;
            focusedEnd = end;
            preview.focusOn(boxes);
        }
        if (start == end) selection.setText(R.string.selection_hint);
        else if (boxes.isEmpty()) selection.setText(R.string.selection_unmatched);
        else selection.setText(getString(R.string.selection_matched, boxes.size()));
    }

    private void pickPhoto() {
        photos.launch(new PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
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
}
