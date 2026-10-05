#include "ocr.h"
#include <fstream>
#include <iostream>
#include <stdexcept>

int main(int argc, char **argv) {
    if (argc != 6) {
        std::cerr << "Usage: ocr-probe MODEL MMPROJ IMAGE TEXT_OUT METRICS_OUT\n";
        return 2;
    }
    try {
        std::atomic<bool> cancel{false};
        const auto result = ocr::recognize(argv[1], argv[2], argv[3], 256, cancel);
        std::ofstream text(argv[4]);
        text << result.text;
        text.close();
        std::ofstream metrics(argv[5]);
        metrics << "{\"elapsed_seconds\":" << result.elapsed_seconds
                << ",\"peak_rss_kib\":" << result.peak_rss_kib
                << ",\"generated_tokens\":" << result.generated_tokens
                << ",\"truncated\":" << (result.truncated ? "true" : "false") << "}\n";
        metrics.close();
        if (!text || !metrics) throw std::runtime_error("Cannot persist probe output");
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
