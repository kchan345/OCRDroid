package io.github.ocrdroid;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Work {
    // Imports, removals, and OCR share one thread so weights are never replaced while inference reads them.
    static final ExecutorService MODELS = Executors.newSingleThreadExecutor();
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private Work() {}
}
