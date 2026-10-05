package io.github.ocrdroid;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends ComponentActivity {
    private OcrViewModel model;
    private DocumentView preview;
    private SelectionEditor editor;
    private TextView status, selection;
    private Spinner precision, script;
    private Button cancel;
    private boolean rendering;
    private Uri cameraUri;
    private final List<View> controls = new ArrayList<>();

    private final ActivityResultLauncher<Uri> folder = registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(), uri -> { if (uri != null) model.importModel(uri); });
    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> photos =
        registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) model.openImage(uri);
        });
    private final ActivityResultLauncher<Uri> camera = registerForActivityResult(
        new ActivityResultContracts.TakePicture(), success -> {
            if (success && cameraUri != null) model.openImage(cameraUri);
        });
    private final ActivityResultLauncher<String> export = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("text/plain"), uri -> { if (uri != null) model.saveText(uri); });

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        model = new ViewModelProvider(this).get(OcrViewModel.class);
        if (saved != null && saved.getString("camera") != null) cameraUri = Uri.parse(saved.getString("camera"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom);
            return insets;
        });
        TextView title = label(R.string.app_name, 25);
        title.setTextColor(Color.rgb(18, 91, 81));
        root.addView(title);
        root.addView(label(R.string.tagline, 14));
        ScrollView settings = new ScrollView(this);
        LinearLayout settingsBody = column();
        settings.addView(settingsBody);
        root.addView(settings, new LinearLayout.LayoutParams(-1, dp(160)));
        settingsBody.addView(label(R.string.device_warning, 12));
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(info);
        if (info.totalMem <= 6_000_000_000L) settingsBody.addView(label(R.string.low_memory, 12));
        settingsBody.addView(button(R.string.import_model, () -> folder.launch(null)));
        LinearLayout choices = row();
        precision = spinner(R.array.precisions);
        script = spinner(R.array.scripts);
        choices.addView(labeledSpinner(R.string.model_label, precision), weighted());
        choices.addView(labeledSpinner(R.string.script_label, script), weighted());
        settingsBody.addView(choices);
        settingsBody.addView(label(R.string.bf16_warning, 12));
        settingsBody.addView(label(R.string.highlight_disclosure, 12));
        LinearLayout inputs = row();
        inputs.addView(button(R.string.camera, this::takePhoto), weighted());
        inputs.addView(button(R.string.photos, () -> photos.launch(
            new androidx.activity.result.PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build())), weighted());
        root.addView(inputs);
        preview = new DocumentView(this);
        preview.setId(R.id.preview);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = row();
        actions.addView(button(R.string.recognize,
            () -> model.recognize(precision.getSelectedItem().toString(), script.getSelectedItemPosition())), weighted());
        cancel = new Button(this);
        cancel.setText(R.string.cancel);
        cancel.setOnClickListener(view -> model.cancel());
        cancel.setEnabled(false);
        actions.addView(cancel);
        root.addView(actions);
        status = label(R.string.ready, 13);
        status.setMaxLines(4);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        editor = new SelectionEditor(this);
        editor.setId(R.id.editor);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextSize(16);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setHint(R.string.editor_hint);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        controls.add(editor);
        selection = label(R.string.selection_hint, 12);
        selection.setMaxLines(2);
        root.addView(selection);
        root.addView(button(R.string.export_text, () -> export.launch("ocr.txt")));
        editor.selectionChanged = (start, end) -> showSelection(start, end);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!rendering) model.edit(value.toString(), start, before, count);
            }
            @Override public void afterTextChanged(Editable value) {
                if (!rendering) showSelection(editor.getSelectionStart(), editor.getSelectionEnd());
            }
        });
        setContentView(root);
        model.status.observe(this, value -> status.setText(value));
        model.busy.observe(this, working -> {
            for (View control : controls) control.setEnabled(!working);
            cancel.setEnabled(working);
            if (working) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        model.importedPrecision.observe(this, value -> precision.setSelection("BF16".equals(value) ? 1 : 0));
        model.revision.observe(this, value -> {
            rendering = true;
            if (!editor.getText().toString().equals(model.text)) editor.setText(model.text);
            preview.setImage(model.image);
            rendering = false;
            showSelection(editor.getSelectionStart(), editor.getSelectionEnd());
        });
    }

    private void showSelection(int start, int end) {
        if (preview == null || selection == null) return;
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

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView label(int resource, int size) {
        TextView label = new TextView(this);
        label.setText(resource); label.setTextSize(size);
        label.setPadding(0, dp(3), 0, dp(3));
        return label;
    }
    private Button button(int resource, Runnable action) {
        Button button = new Button(this);
        button.setText(resource);
        button.setOnClickListener(view -> action.run());
        controls.add(button);
        return button;
    }
    private Spinner spinner(int resource) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, resource, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        controls.add(spinner);
        return spinner;
    }
    private LinearLayout labeledSpinner(int resource, Spinner spinner) {
        LinearLayout layout = column();
        layout.addView(label(resource, 12));
        spinner.setContentDescription(getString(resource));
        layout.addView(spinner);
        return layout;
    }
}
