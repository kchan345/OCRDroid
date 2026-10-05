package io.github.ocrdroid;

import android.content.Context;
import android.widget.EditText;
import java.util.function.BiConsumer;

public final class SelectionEditor extends EditText {
    public BiConsumer<Integer, Integer> selectionChanged;
    public SelectionEditor(Context context) { super(context); }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionChanged != null) selectionChanged.accept(start, end);
    }
}
