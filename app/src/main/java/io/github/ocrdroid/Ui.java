package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;

/** Small helpers for the code-built UI shared by the scan and settings screens. */
final class Ui {
    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    static LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }

    static TextView label(Context context, int resource, int size) {
        TextView label = new TextView(context);
        if (resource != 0) label.setText(resource);
        label.setTextSize(size);
        label.setPadding(0, dp(context, 3), 0, dp(context, 3));
        return label;
    }

    static TextView heading(Context context, int resource) {
        TextView heading = label(context, resource, 19);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(MainActivity.ACCENT);
        heading.setPadding(0, dp(context, 14), 0, dp(context, 4));
        heading.setAccessibilityHeading(true);
        return heading;
    }

    static TextView panelTitle(Context context, int resource) {
        TextView title = label(context, resource, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setAccessibilityHeading(true);
        return title;
    }

    static Button button(Context context, int resource, Runnable action) {
        Button button = new Button(context);
        button.setText(resource);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static TextView menuItem(Context context, int id, int resource, Runnable action) {
        TextView item = label(context, resource, 17);
        item.setId(id);
        item.setMinHeight(dp(context, 48));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        item.setTextColor(Color.rgb(29, 35, 33));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        item.setBackgroundResource(ripple.resourceId);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> action.run());
        return item;
    }

    static Toolbar toolbar(Context context, int title, int icon, int iconDescription, Runnable navigate) {
        Toolbar toolbar = new Toolbar(context);
        toolbar.setId(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(Color.rgb(214, 236, 230));
        toolbar.setBackgroundColor(MainActivity.ACCENT);
        toolbar.setNavigationIcon(icon);
        toolbar.setNavigationContentDescription(iconDescription);
        toolbar.setNavigationOnClickListener(view -> navigate.run());
        return toolbar;
    }
}
