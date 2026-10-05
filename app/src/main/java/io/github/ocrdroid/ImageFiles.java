package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class ImageFiles {
    private ImageFiles() {}

    public static Bitmap read(Context context, Uri uri) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            float scale = Math.min(1f, 2048f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
        });
    }

    public static void write(Bitmap bitmap, File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("Cannot normalize photo");
            }
            output.getFD().sync();
        }
    }
}
