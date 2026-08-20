#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>

#define TAG "VJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static const llama_vocab *g_vocab = nullptr;
static bool g_loaded = false;

static std::string j2s(JNIEnv *env, jstring js) {
    if (!js) return "";
    const char *c = env->GetStringUTFChars(js, nullptr);
    if (!c) return "";
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ═══ nativeLoadModel ═══
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv *env, jobject, jstring jpath, jint ctxSize, jint threads)
{
    std::string path = j2s(env, jpath);
    LOGI("LOAD: %s ctx=%d th=%d", path.c_str(), ctxSize, threads);

    if (path.empty()) { LOGE("Path empty"); return JNI_FALSE; }

    // Free old
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr; g_loaded = false;

    // NO llama_backend_init() — it hangs on Android!

    // Load model
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    LOGI("Loading model file...");
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { LOGE("Model FAIL"); return JNI_FALSE; }
    LOGI("Model loaded OK");

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        LOGE("Vocab FAIL");
        llama_model_free(g_model); g_model = nullptr;
        return JNI_FALSE;
    }

    // Context
    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)std::max(256, (int)ctxSize);
    cp.n_batch = cp.n_ctx;
    cp.n_threads = (uint32_t)threads;
    cp.n_threads_batch = (uint32_t)threads;

    LOGI("Creating context n_ctx=%u", cp.n_ctx);
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Ctx FAIL");
        llama_model_free(g_model); g_model = nullptr; g_vocab = nullptr;
        return JNI_FALSE;
    }

    g_loaded = true;
    LOGI("=== LOAD COMPLETE ===");
    return JNI_TRUE;
}

// ═══ nativeGenerate ═══
extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject, jstring jprompt, jint maxTokens,
    jfloat temp, jfloat topP, jint topK, jobject)
{
    if (!g_loaded || !g_model || !g_ctx || !g_vocab) {
        LOGE("Not loaded");
        return env->NewStringUTF("النموذج غير جاهز");
    }

    std::string prompt = j2s(env, jprompt);
    LOGI("GEN: %zu chars max=%d", prompt.size(), maxTokens);

    if (prompt.empty()) {
        return env->NewStringUTF("النص فارغ");
    }

    // ═══ Tokenize ═══
    // First call: returns NEGATIVE count (official llama.cpp pattern)
    int32_t raw = llama_tokenize(
        g_vocab,
        prompt.c_str(), (int32_t)prompt.size(),
        nullptr, 0,
        true, true
    );

    int32_t n_tok = (raw < 0) ? -raw : raw;
    LOGI("Tokens needed: %d (raw=%d)", n_tok, raw);

    if (n_tok <= 0) {
        LOGE("Tokenize count failed");
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    // Check context
    int32_t n_ctx = (int32_t)llama_n_ctx(g_ctx);
    if (n_tok >= n_ctx) {
        LOGE("Too long: %d >= %d", n_tok, n_ctx);
        return env->NewStringUTF("النص أطول من السعة");
    }

    // Fill tokens
    std::vector<llama_token> tokens(n_tok);
    int32_t filled = llama_tokenize(
        g_vocab,
        prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(), n_tok,
        true, true
    );

    if (filled < 0) {
        LOGE("Tokenize fill failed: %d", filled);
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    n_tok = filled;
    LOGI("Tokenized: %d tokens", n_tok);

    // ═══ Decode prompt ═══
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tok);
    LOGI("Decoding prompt...");

    int dr = llama_decode(g_ctx, batch);
    LOGI("Decode result: %d", dr);

    if (dr != 0) {
        LOGE("Decode FAILED: %d", dr);
        return env->NewStringUTF("خطأ في معالجة النص");
    }
    LOGI("Prompt decoded OK");

    // ═══ Sampler (create fresh each time) ═══
    auto sp = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP > 0.01f ? topP : 0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp > 0.01f ? temp : 0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

    // ═══ Generate ═══
    int32_t limit = std::max(1, std::min((int32_t)maxTokens, n_ctx - n_tok - 1));
    LOGI("Generate up to %d tokens", limit);

    std::string output;

    for (int32_t i = 0; i < limit; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, tok)) {
            LOGI("EOG at %d", i);
            break;
        }

        char buf[512];
        int32_t len = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) {
            output.append(buf, len);
        }

        // Feed next token
        batch = llama_batch_get_one(&tok, 1);
        dr = llama_decode(g_ctx, batch);
        if (dr != 0) {
            LOGE("Gen decode@%d: %d", i, dr);
            break;
        }

        if ((i + 1) % 10 == 0) {
            LOGI("..%d tokens", i + 1);
        }
    }

    llama_sampler_free(sampler);
    LOGI("DONE: %zu chars", output.size());

    if (output.empty()) {
        return env->NewStringUTF("النموذج لم يُنتج رداً");
    }
    return env->NewStringUTF(output.c_str());
}

// ═══ nativeGetMemUsage ═══
extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(JNIEnv *, jobject) {
    return g_ctx ? (jlong)llama_state_get_size(g_ctx) : 0;
}

// ═══ nativeUnload ═══
extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(JNIEnv *, jobject) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr; g_loaded = false;
    LOGI("UNLOADED");
}
