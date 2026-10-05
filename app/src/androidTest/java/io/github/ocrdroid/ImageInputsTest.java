package io.github.ocrdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ImageInputsTest {
    @Test public void contentUriRotationResolutionAndTransparency() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "camera");
        assertTrue(directory.isDirectory() || directory.mkdir());
        File jpeg = new File(directory, "orientation.jpg");
        Bitmap source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        try (var output = new FileOutputStream(jpeg)) {
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, output));
        }
        source.recycle();
        ExifInterface exif = new ExifInterface(jpeg);
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        Bitmap rotated = ImageFiles.read(context, FileProvider.getUriForFile(
            context, context.getPackageName() + ".files", jpeg));
        assertEquals(80, rotated.getWidth());
        assertEquals(120, rotated.getHeight());
        rotated.recycle();

        File png = new File(directory, "large-transparent.png");
        Bitmap large = Bitmap.createBitmap(4000, 1000, Bitmap.Config.ARGB_8888);
        ImageFiles.write(large, png);
        large.recycle();
        Bitmap resized = ImageFiles.read(context, FileProvider.getUriForFile(
            context, context.getPackageName() + ".files", png));
        assertEquals(2048, resized.getWidth());
        assertEquals(512, resized.getHeight());
        assertEquals(Color.WHITE, resized.getPixel(100, 100));
        resized.recycle();
    }
}
