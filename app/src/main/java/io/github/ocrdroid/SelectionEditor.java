package io.github.ocrdroid;

import android.content.Context;
import androidx.appcompat.widget.AppCompatEditText;
import java.util.function.BiConsumer;

public final class SelectionEditor extends AppCompatEditText {
    public BiConsumer<Integer, Integer> selectionChanged;
    public SelectionEditor(Context context) { super(context); }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionChanged != null) selectionChanged.accept(start, end);
    }
}
