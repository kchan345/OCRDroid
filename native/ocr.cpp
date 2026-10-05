#include "ocr.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <sys/resource.h>
#include <thread>
#include <vector>

namespace {
template <typename T, void (*Free)(T *)>
using Handle = std::unique_ptr<T, decltype(Free)>;

bool aborted(void *data) {
    return static_cast<std::atomic<bool> *>(data)->load();
}

const char *instruction =
    "\nExtract all readable content from the image in natural human reading order and output "
    "the result as a single Markdown document. For charts or images, represent them using an "
    "HTML image tag: <img src=\"images/bbox_{left}_{top}_{right}_{bottom}.jpg\" />, where left, "
    "top, right, bottom are bounding box coordinates scaled to [0, 1000). Format formulas as "
    "LaTeX. Format tables as HTML: <table>...</table>. Transcribe all other text as standard "
    "Markdown. Preserve the original text without translation or paraphrasing.";
}

ocr::Result ocr::recognize(const std::string &model_path, const std::string &projector_path,
                         const std::string &image_path, int max_tokens, std::atomic<bool> &cancel) {
    if (max_tokens < 1 || max_tokens > 2048) {
        throw std::invalid_argument("Output token limit must be between 1 and 2048");
    }
    const auto start = std::chrono::steady_clock::now();
    static std::once_flag initialized;
    std::call_once(initialized, llama_backend_init);
    auto check_cancel = [&] {
        if (cancel.load()) throw std::runtime_error("OCR cancelled");
    };
    check_cancel();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap = true;
    Handle<llama_model, llama_model_free> model(
        llama_model_load_from_file(model_path.c_str(), mp), llama_model_free);
    if (!model) throw std::runtime_error("Cannot load language model; check GGUF and available RAM");
    check_cancel();
    const int threads = std::max(1, std::min(4, static_cast<int>(std::thread::hardware_concurrency())));
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.abort_callback = aborted;
    cp.abort_callback_data = &cancel;
    Handle<llama_context, llama_free> ctx(llama_init_from_model(model.get(), cp), llama_free);
    if (!ctx) throw std::runtime_error("Cannot allocate inference context");
    auto vp = mtmd_context_params_default();
    vp.use_gpu = false;
    vp.n_threads = threads;
    vp.warmup = false;
    vp.image_min_tokens = 196;
    vp.image_max_tokens = 1024;
    Handle<mtmd_context, mtmd_free> vision(
        mtmd_init_from_file(projector_path.c_str(), model.get(), vp), mtmd_free);
    if (!vision) throw std::runtime_error("Cannot load matching vision projector");
    check_cancel();
    const auto loaded = mtmd_helper_bitmap_init_from_file(
        vision.get(), image_path.c_str(), false, mtmd_helper_init_opt_default());
    Handle<mtmd_bitmap, mtmd_bitmap_free> image(loaded.bitmap, mtmd_bitmap_free);
    if (!image) throw std::runtime_error("Cannot decode image");
    // This is the model's pinned chat template with enable_thinking=false.
    const std::string prompt = std::string("<|im_start|>user\n") + mtmd_default_marker() +
        instruction + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
    mtmd_input_text text{prompt.data(), prompt.size(), true, true};
    const mtmd_bitmap *images[] = {image.get()};
    Handle<mtmd_input_chunks, mtmd_input_chunks_free> chunks(
        mtmd_input_chunks_init(), mtmd_input_chunks_free);
    if (mtmd_tokenize(vision.get(), chunks.get(), &text, images, 1) != 0) {
        throw std::runtime_error("Cannot tokenize image and OCR prompt");
    }
    if (mtmd_helper_get_n_tokens(chunks.get()) + max_tokens > cp.n_ctx) {
        throw std::runtime_error("Image and requested output exceed context budget");
    }
    llama_pos past = 0;
    if (mtmd_helper_eval_chunks(vision.get(), ctx.get(), chunks.get(), 0, 0,
                               cp.n_batch, true, &past) != 0) {
        check_cancel();
        throw std::runtime_error("Image encoding or prompt evaluation failed");
    }
    check_cancel();
    Handle<llama_sampler, llama_sampler_free> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    Handle<llama_batch_ext, llama_batch_ext_free> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
    const auto *vocab = llama_model_get_vocab(model.get());
    Result result;
    result.truncated = true;
    for (int i = 0; i < max_tokens; ++i) {
        check_cancel();
        const auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) {
            result.truncated = false;
            break;
        }
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (length < 0) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (length < 0) throw std::runtime_error("Cannot decode output token");
        result.text.append(piece.data(), length);
        result.generated_tokens++;
        llama_batch_ext_clear(batch.get());
        const int idx = llama_batch_ext_add_token(batch.get(), 0, token);
        if (idx < 0 || !llama_batch_ext_set_pos(batch.get(), idx, &past) ||
            !llama_batch_ext_set_output_logits(batch.get(), idx, true)) {
            throw std::runtime_error("Cannot prepare token batch");
        }
        ++past;
        if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            check_cancel();
            throw std::runtime_error("Token generation failed");
        }
    }
    result.elapsed_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) throw std::runtime_error("Cannot measure peak memory");
    result.peak_rss_kib = usage.ru_maxrss;
    return result;
}
