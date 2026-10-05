package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Collections;
import java.util.List;

public final class DocumentView extends View {
    private Bitmap image;
    private List<TextAnchors.Box> boxes = Collections.emptyList();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public DocumentView(Context context) {
        super(context);
        setContentDescription(context.getString(R.string.preview_description));
        setBackgroundColor(Color.rgb(225, 230, 225));
    }

    public void setImage(Bitmap bitmap) { image = bitmap; boxes = Collections.emptyList(); invalidate(); }
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
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(image, 0, 0, paint);
        for (TextAnchors.Box box : boxes) {
            RectF rect = new RectF(box.left, box.top, box.right, box.bottom);
            paint.setColor(0x66FFD43B);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(rect, paint);
            paint.setColor(0xFFAD6700);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 / scale);
            canvas.drawRect(rect, paint);
        }
        canvas.restore();
    }
}
