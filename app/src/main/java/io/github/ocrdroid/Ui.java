package io.github.ocrdroid;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.appbar.MaterialToolbar;

/** Helpers for the code-built Material 3 UI shared by the scan and settings screens. */
final class Ui {
    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Resolves a theme colour attribute such as {@code R.attr.colorPrimary}. */
    static int color(Context context, int attribute) {
        return MaterialColors.getColor(context, attribute, 0xFF808080);
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }

    static LinearLayout.LayoutParams margins(Context context, int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, dp(context, top), 0, dp(context, bottom));
        return params;
    }

    static TextView text(Context context, int resource, int appearance, int colorAttribute) {
        TextView view = new TextView(context);
        view.setTextAppearance(appearance);
        if (resource != 0) view.setText(resource);
        view.setTextColor(color(context, colorAttribute));
        return view;
    }

    /** Body text; {@code size} picks the Material type scale (16+ large, 14-15 medium, otherwise small). */
    static TextView label(Context context, int resource, int size) {
        int appearance = size >= 16 ? com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
            : size >= 14 ? com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
            : com.google.android.material.R.style.TextAppearance_Material3_BodySmall;
        TextView label = text(context, resource, appearance, com.google.android.material.R.attr.colorOnSurfaceVariant);
        label.setPadding(0, dp(context, 2), 0, dp(context, 2));
        return label;
    }

    static TextView headline(Context context, int resource) {
        TextView view = text(context, resource, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
            com.google.android.material.R.attr.colorOnSurface);
        view.setAccessibilityHeading(true);
        return view;
    }

    static TextView heading(Context context, int resource) {
        TextView heading = text(context, resource, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
            androidx.appcompat.R.attr.colorPrimary);
        heading.setPadding(0, dp(context, 4), 0, dp(context, 6));
        heading.setAccessibilityHeading(true);
        return heading;
    }

    static TextView panelTitle(Context context, int resource) {
        TextView title = text(context, resource, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge,
            com.google.android.material.R.attr.colorOnSurfaceVariant);
        title.setAccessibilityHeading(true);
        return title;
    }

    private static MaterialButton styled(Context context, int overlay, int resource, int icon, Runnable action) {
        MaterialButton button = new MaterialButton(overlay == 0 ? context : new ContextThemeWrapper(context, overlay));
        if (resource != 0) button.setText(resource);
        if (icon != 0) button.setIconResource(icon);
        button.setMaxLines(1);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static MaterialButton filled(Context context, int resource, int icon, Runnable action) {
        return styled(context, 0, resource, icon, action);
    }

    static MaterialButton tonal(Context context, int resource, int icon, Runnable action) {
        return styled(context, R.style.ThemeOverlay_OCRDroid_TonalButton, resource, icon, action);
    }

    static MaterialButton outlined(Context context, int resource, int icon, Runnable action) {
        return styled(context, R.style.ThemeOverlay_OCRDroid_OutlinedButton, resource, icon, action);
    }

    static MaterialButton textButton(Context context, int resource, int icon, Runnable action) {
        return styled(context, R.style.ThemeOverlay_OCRDroid_TextButton, resource, icon, action);
    }

    /** Tonal icon-only button; the description doubles as tooltip. */
    static MaterialButton iconButton(Context context, int icon, int description, Runnable action) {
        MaterialButton button = styled(context, R.style.ThemeOverlay_OCRDroid_IconButton, 0, icon, action);
        String label = context.getString(description);
        button.setContentDescription(label);
        button.setTooltipText(label);
        return button;
    }

    static MaterialButton segment(Context context, int id, int resource, int icon) {
        MaterialButton button = new MaterialButton(new ContextThemeWrapper(context, R.style.ThemeOverlay_OCRDroid_SegmentButton));
        button.setId(id);
        if (resource != 0) button.setText(resource);
        if (icon != 0) button.setIconResource(icon);
        button.setCheckable(true);
        return button;
    }

    /** Filled Material card containing a padded vertical column, returned as {@code card.getChildAt(0)}. */
    static MaterialCardView card(Context context) {
        MaterialCardView card = new MaterialCardView(context, null,
            com.google.android.material.R.attr.materialCardViewFilledStyle);
        card.setRadius(dp(context, 20));
        LinearLayout content = column(context);
        int pad = dp(context, 16);
        content.setPadding(pad, pad, pad, pad);
        card.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    static LinearLayout content(MaterialCardView card) { return (LinearLayout) card.getChildAt(0); }

    static MaterialToolbar toolbar(Context context, int title, int icon, int iconDescription, Runnable navigate) {
        MaterialToolbar toolbar = new MaterialToolbar(context);
        toolbar.setId(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationIcon(icon);
        toolbar.setNavigationContentDescription(iconDescription);
        toolbar.setNavigationOnClickListener(view -> navigate.run());
        toolbar.setNavigationIconTint(color(context, com.google.android.material.R.attr.colorOnSurface));
        toolbar.setTitleTextColor(color(context, com.google.android.material.R.attr.colorOnSurface));
        toolbar.setSubtitleTextColor(color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        return toolbar;
    }

    static void gone(View view, boolean hidden) { view.setVisibility(hidden ? View.GONE : View.VISIBLE); }
}
