package io.github.ocrdroid;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Localizer {
    private Localizer() {}

    public static List<TextAnchors.Region> locate(Bitmap bitmap, int script)
            throws ExecutionException, InterruptedException, TimeoutException {
        TextRecognizer recognizer = switch (script) {
            case 0 -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            case 1 -> TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            case 2 -> TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
            case 3 -> TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            case 4 -> TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
            default -> throw new IllegalArgumentException("Unknown highlight language");
        };
        try {
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 90, TimeUnit.SECONDS);
            List<TextAnchors.Region> regions = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect box = line.getBoundingBox();
                    if (box != null && !box.isEmpty()) {
                        regions.add(new TextAnchors.Region(line.getText(),
                            new TextAnchors.Box(box.left, box.top, box.right, box.bottom)));
                    }
                }
            }
            return regions;
        } finally {
            recognizer.close();
        }
    }
}
