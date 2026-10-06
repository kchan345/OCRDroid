package io.github.ocrdroid;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;

/** Model configuration page: engine choice, on-device model folders, cloud endpoint, and highlight language. */
public final class SettingsActivity extends AppCompatActivity {
    private SettingsViewModel model;
    private RadioGroup engines;
    private MaterialRadioButton q4, bf16, cloud;
    private TextView status, q4State, bf16State, cleartext;
    private Button removeQ4, removeBf16;
    private EditText url, served, key;
    private boolean updating;
    private final List<View> controls = new ArrayList<>();

    private final ActivityResultLauncher<android.net.Uri> folder = registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(), uri -> { if (uri != null) model.importModel(uri); });

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        model = new ViewModelProvider(this).get(SettingsViewModel.class);
        LinearLayout root = Ui.column(this);
        root.addView(Ui.toolbar(this, R.string.nav_settings, R.drawable.ic_back, R.string.navigate_up, this::finish));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = Ui.column(this);
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, Ui.dp(this, 4), pad, pad);
        scroll.addView(body);

        status = Ui.label(this, 0, 13);
        status.setId(R.id.settings_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);

        LinearLayout section = section(body, R.string.section_engine);
        engines = new RadioGroup(this);
        q4 = radio(R.id.engine_q4);
        bf16 = radio(R.id.engine_bf16);
        cloud = radio(R.id.engine_cloud);
        engines.addView(q4);
        engines.addView(bf16);
        engines.addView(cloud);
        engines.setOnCheckedChangeListener((group, checked) -> {
            if (updating) return;
            model.settings.setBackend(checked == R.id.engine_bf16 ? AppSettings.Backend.LOCAL_BF16
                : checked == R.id.engine_cloud ? AppSettings.Backend.CLOUD : AppSettings.Backend.LOCAL_Q4);
            refresh();
        });
        section.addView(engines);
        section.addView(Ui.label(this, R.string.engine_privacy, 12));

        section = section(body, R.string.section_local);
        section.addView(Ui.label(this, R.string.local_help, 13));
        Button importButton = control(Ui.tonal(this, R.string.import_model, R.drawable.ic_memory, () -> folder.launch(null)));
        importButton.setId(R.id.import_model);
        section.addView(importButton, Ui.margins(this, -1, -2, 8, 4));
        q4State = Ui.label(this, 0, 14);
        removeQ4 = control(Ui.textButton(this, R.string.remove, 0, () -> model.removeModel("Q4_K_M")));
        section.addView(modelRow(q4State, removeQ4));
        bf16State = Ui.label(this, 0, 14);
        removeBf16 = control(Ui.textButton(this, R.string.remove, 0, () -> model.removeModel("BF16")));
        section.addView(modelRow(bf16State, removeBf16));
        section.addView(Ui.label(this, R.string.bf16_warning, 12));

        section = section(body, R.string.section_cloud);
        section.addView(Ui.label(this, R.string.cloud_help, 13));
        url = field(section, R.id.cloud_url, R.string.cloud_url, R.string.cloud_url_hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        served = field(section, R.id.cloud_model, R.string.cloud_model, R.string.cloud_model_hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key = field(section, R.id.cloud_key, R.string.cloud_key, R.string.cloud_key_hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // The decrypted key is never written into saved instance state.
        key.setSaveEnabled(false);
        key.setText(model.settings.apiKey());
        if (saved == null) {
            url.setText(model.settings.cloudUrl());
            served.setText(model.settings.cloudModel());
        }
        cleartext = Ui.label(this, R.string.cleartext_warning, 12);
        cleartext.setTextColor(Ui.color(this, androidx.appcompat.R.attr.colorError));
        section.addView(cleartext);
        url.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) { showCleartextWarning(); }
        });
        LinearLayout cloudActions = Ui.row(this);
        Button test = control(Ui.outlined(this, R.string.test_connection, R.drawable.ic_cloud, () -> model.testConnection(
            url.getText().toString(), served.getText().toString(), key.getText().toString())));
        test.setId(R.id.test_connection);
        cloudActions.addView(test, Ui.weighted());
        Button save = control(Ui.filled(this, R.string.save_cloud, R.drawable.ic_save, () -> model.saveCloud(
            url.getText().toString(), served.getText().toString(), key.getText().toString())));
        save.setId(R.id.save_cloud);
        LinearLayout.LayoutParams saveParams = Ui.weighted();
        saveParams.setMarginStart(Ui.dp(this, 12));
        cloudActions.addView(save, saveParams);
        section.addView(cloudActions, Ui.margins(this, -1, -2, 8, 0));

        section = section(body, R.string.section_highlight);
        TextView scriptLabel = Ui.label(this, R.string.script_label, 13);
        section.addView(scriptLabel);
        Spinner script = new Spinner(this);
        script.setId(R.id.script);
        script.setContentDescription(getString(R.string.script_label));
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.scripts,
            android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        script.setAdapter(adapter);
        script.setSelection(model.settings.script());
        script.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                model.settings.setScript(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        script.setMinimumHeight(Ui.dp(this, 48));
        section.addView(script);
        section.addView(Ui.label(this, R.string.highlight_disclosure, 12));
        setContentView(root);

        model.status.observe(this, value -> status.setText(value));
        model.busy.observe(this, working -> { for (View control : controls) control.setEnabled(!working); refresh(); });
        model.revision.observe(this, value -> refresh());
        model.suggestedModel.observe(this, value -> {
            if (value != null && served.getText().toString().trim().isEmpty()) served.setText(value);
        });
    }

    private void refresh() {
        updating = true;
        boolean hasQ4 = model.models.has("Q4_K_M");
        boolean hasBf16 = model.models.has("BF16");
        q4.setText(getString(R.string.engine_option_q4, getString(hasQ4 ? R.string.imported : R.string.not_imported)));
        bf16.setText(getString(R.string.engine_option_bf16, getString(hasBf16 ? R.string.imported : R.string.not_imported)));
        cloud.setText(getString(R.string.engine_option_cloud, getString(
            model.settings.cloudConfigured() ? R.string.configured : R.string.not_configured)));
        engines.check(switch (model.settings.backend()) {
            case LOCAL_BF16 -> R.id.engine_bf16;
            case CLOUD -> R.id.engine_cloud;
            default -> R.id.engine_q4;
        });
        q4State.setText(getString(R.string.model_state, "Q4_K_M", getString(hasQ4 ? R.string.imported : R.string.not_imported)));
        bf16State.setText(getString(R.string.model_state, "BF16", getString(hasBf16 ? R.string.imported : R.string.not_imported)));
        boolean idle = !Boolean.TRUE.equals(model.busy.getValue());
        removeQ4.setEnabled(idle && hasQ4);
        removeBf16.setEnabled(idle && hasBf16);
        showCleartextWarning();
        updating = false;
    }

    private void showCleartextWarning() {
        boolean plain;
        try { plain = CloudOcr.unencrypted(CloudOcr.endpoint(url.getText().toString())); }
        catch (IllegalArgumentException invalid) { plain = false; }
        cleartext.setVisibility(plain ? View.VISIBLE : View.GONE);
    }

    private LinearLayout section(LinearLayout body, int title) {
        MaterialCardView card = Ui.card(this);
        LinearLayout content = Ui.content(card);
        content.addView(Ui.heading(this, title));
        body.addView(card, Ui.margins(this, -1, -2, 6, 6));
        return content;
    }

    private MaterialRadioButton radio(int id) {
        MaterialRadioButton button = new MaterialRadioButton(this);
        button.setId(id);
        button.setMinHeight(Ui.dp(this, 48));
        controls.add(button);
        return button;
    }

    private LinearLayout modelRow(TextView state, Button remove) {
        LinearLayout row = Ui.row(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(state, Ui.weighted());
        row.addView(remove);
        return row;
    }

    private EditText field(LinearLayout body, int id, int label, int hint, int type) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(getString(label));
        layout.setPlaceholderText(getString(hint));
        TextInputEditText field = new TextInputEditText(layout.getContext());
        field.setId(id);
        field.setInputType(type);
        field.setSingleLine(true);
        layout.addView(field, new LinearLayout.LayoutParams(-1, -2));
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) {
            layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        controls.add(field);
        body.addView(layout, Ui.margins(this, -1, -2, 8, 0));
        return field;
    }

    private <T extends View> T control(T view) { controls.add(view); return view; }
}
