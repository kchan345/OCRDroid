package io.github.ocrdroid;

import java.nio.charset.StandardCharsets;

public final class OcrEngine {
    static { System.loadLibrary("ocr-jni"); }
    private OcrEngine() {}

    public static native void nativePrepare();
    public static native void nativeCancel();
    public static native Result nativeRecognize(String model, String projector, String image, int limit);

    public static final class Result {
        public final String text;
        public final boolean truncated;
        public final int tokens;
        public final double seconds;
        public final long peakRssKib;

        public Result(byte[] utf8, boolean truncated, int tokens, double seconds, long peakRssKib) {
            this.text = new String(utf8, StandardCharsets.UTF_8);
            this.truncated = truncated;
            this.tokens = tokens;
            this.seconds = seconds;
            this.peakRssKib = peakRssKib;
        }
    }
}
