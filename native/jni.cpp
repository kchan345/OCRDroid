#include "ocr.h"
#include <jni.h>
#include <mutex>
#include <stdexcept>

namespace {
std::atomic<bool> cancellation{false};
std::mutex inference_mutex;

std::string path(JNIEnv *env, jstring value) {
    if (!value) throw std::invalid_argument("Missing model or image path");
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ocrdroid_OcrEngine_nativePrepare(JNIEnv *, jclass) {
    cancellation.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ocrdroid_OcrEngine_nativeCancel(JNIEnv *, jclass) {
    cancellation.store(true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_ocrdroid_OcrEngine_nativeRecognize(
        JNIEnv *env, jclass, jstring model, jstring projector, jstring image, jint limit) {
    try {
        std::unique_lock<std::mutex> guard(inference_mutex, std::try_to_lock);
        if (!guard.owns_lock()) throw std::runtime_error("An OCR task is already running");
        auto result = ocr::recognize(path(env, model), path(env, projector), path(env, image),
                                     limit, cancellation);
        jclass cls = env->FindClass("io/github/ocrdroid/OcrEngine$Result");
        if (!cls) return nullptr;
        auto constructor = env->GetMethodID(cls, "<init>", "([BZIDJ)V");
        if (!constructor) return nullptr;
        auto bytes = env->NewByteArray(static_cast<jsize>(result.text.size()));
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.text.size()),
                               reinterpret_cast<const jbyte *>(result.text.data()));
        if (env->ExceptionCheck()) return nullptr;
        return env->NewObject(cls, constructor, bytes, static_cast<jboolean>(result.truncated),
                              static_cast<jint>(result.generated_tokens), result.elapsed_seconds,
                              static_cast<jlong>(result.peak_rss_kib));
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        }
        return nullptr;
    }
}
