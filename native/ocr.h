#pragma once
#include <atomic>
#include <string>

namespace ocr {
struct Result {
    std::string text;
    bool truncated = false;
    int generated_tokens = 0;
    double elapsed_seconds = 0;
    long peak_rss_kib = 0;
};
Result recognize(const std::string &model, const std::string &projector,
                 const std::string &image, int max_tokens, std::atomic<bool> &cancel);
}
