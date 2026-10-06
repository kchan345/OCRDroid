package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import java.util.Collections;
import java.util.List;

/**
 * Shows the page with highlights. When the original image is kept, it shows the whole original with
 * the OCR region outlined; highlight boxes are in OCR-page coordinates and are offset into it.
 */
public final class DocumentView extends View {
    private Bitmap image;
    private Rect region;
    private List<TextAnchors.Box> boxes = Collections.emptyList();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private int accent = 0xFF006A60;

    public DocumentView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.preview_description));
    }

    public void setAccent(int color) { accent = color; invalidate(); }

    public void setImage(Bitmap bitmap) { setImage(bitmap, null); }

    /** {@code area} is the OCR region within {@code bitmap}, or null when the bitmap is the OCR page. */
    public void setImage(Bitmap bitmap, Rect area) {
        if (bitmap == image && java.util.Objects.equals(area, region)) return;
        image = bitmap;
        region = area;
        boxes = Collections.emptyList();
        invalidate();
    }

    public void highlight(List<TextAnchors.Box> selection) { boxes = selection; invalidate(); }
    int highlightedRegionCount() { return boxes.size(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image == null) return;
        float scale = Math.min((float) getWidth() / image.getWidth(), (float) getHeight() / image.getHeight());
        float dx = (getWidth() - image.getWidth() * scale) / 2;
        float dy = (getHeight() - image.getHeight() * scale) / 2;
        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(image, 0, 0, paint);
        float ox = 0, oy = 0;
        if (region != null) {
            ox = region.left;
            oy = region.top;
            paint.setColor(0x88000000);
            canvas.drawRect(0, 0, image.getWidth(), region.top, paint);
            canvas.drawRect(0, region.bottom, image.getWidth(), image.getHeight(), paint);
            canvas.drawRect(0, region.top, region.left, region.bottom, paint);
            canvas.drawRect(region.right, region.top, image.getWidth(), region.bottom, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(accent);
            paint.setStrokeWidth(2.5f / scale);
            canvas.drawRect(region, paint);
        }
        for (TextAnchors.Box item : boxes) {
            box.set(item.left + ox, item.top + oy, item.right + ox, item.bottom + oy);
            paint.setColor(0x66FFD43B);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(box, paint);
            paint.setColor(0xFFAD6700);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 / scale);
            canvas.drawRect(box, paint);
        }
        canvas.restore();
    }
}
