package io.github.ocrdroid;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Private debug-only provider exercises the same SAF import path as a user folder. */
public final class FixtureDocuments extends DocumentsProvider {
    private static final String[] COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED
    };
    @Override public boolean onCreate() { return true; }
    private File file(String id) throws FileNotFoundException {
        File root = new File(getContext().getFilesDir(), "test-source");
        if ("source".equals(id)) return root;
        if (id.contains("/") || id.contains("\\") || id.contains("..")) throw new FileNotFoundException();
        return new File(root, id);
    }
    private void add(MatrixCursor cursor, String id, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, id);
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_FLAGS, 0);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        cursor.addRow(new Object[]{"fixtures", "source", "CI fixtures", 0});
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        add(cursor, id, file(id));
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String order) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        File[] files = file(id).listFiles();
        if (files == null) throw new FileNotFoundException(id);
        for (File child : files) add(cursor, child.getName(), child);
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixtures are read-only");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public boolean isChildDocument(String parent, String child) {
        return "source".equals(parent) && !"source".equals(child) && !child.contains("/") && !child.contains("..");
    }
}
