#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstring>

#define TAG "VJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model    *g_model   = nullptr;
static llama_context  *g_ctx     = nullptr;
static llama_sampler  *g_sampler = nullptr;
static const llama_vocab *g_vocab = nullptr;
static std::mutex g_mutex;

// ═══════════════════════════════════════════
// Helper: jstring → std::string
// ═══════════════════════════════════════════
static std::string j2s(JNIEnv *env, jstring js) {
    if (!js) return "";
    const char *c = env->GetStringUTFChars(js, nullptr);
    if (!c) return "";
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ═══════════════════════════════════════════
// nativeLoadModel(path, contextSize, threads)
// ═══════════════════════════════════════════
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv *env, jobject,
    jstring jpath, jint ctxSize, jint threads)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    std::string path = j2s(env, jpath);
    LOGI("LOAD: %s ctx=%d th=%d", path.c_str(), ctxSize, threads);

    if (path.empty()) { LOGE("Path empty"); return JNI_FALSE; }

    // Free old
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_vocab = nullptr;

    // Backend init
    llama_backend_init();

    // Load model
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    LOGI("Loading model file...");
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { LOGE("Model FAIL"); return JNI_FALSE; }
    LOGI("Model OK");

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) { LOGE("Vocab FAIL"); llama_model_free(g_model); g_model = nullptr; return JNI_FALSE; }

    // Context
    auto cp = llama_context_default_params();
    cp.n_ctx          = (uint32_t)std::max(256, (int)ctxSize);
    cp.n_batch        = cp.n_ctx;
    cp.n_threads      = (uint32_t)threads;
    cp.n_threads_batch = (uint32_t)threads;

    LOGI("Creating context n_ctx=%u n_batch=%u threads=%u", cp.n_ctx, cp.n_batch, cp.n_threads);
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("Ctx FAIL"); llama_model_free(g_model); g_model = nullptr; g_vocab = nullptr; return JNI_FALSE; }

    // Sampler (persistent)
    auto sp = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(1234));

    LOGI("=== LOAD COMPLETE ===");
    return JNI_TRUE;
}

// ═══════════════════════════════════════════
// nativeGenerate(prompt, maxTokens, temp, topP, topK, callback)
// ═══════════════════════════════════════════
extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject,
    jstring jprompt, jint maxTokens,
    jfloat temp, jfloat topP, jint topK,
    jobject /* callback */)
{
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx || !g_vocab || !g_sampler) {
        LOGE("Not initialized");
        return env->NewStringUTF("النموذج غير جاهز");
    }

    std::string prompt = j2s(env, jprompt);
    LOGI("GEN: %zu chars, max=%d", prompt.size(), maxTokens);

    if (prompt.empty()) {
        LOGE("Prompt empty");
        return env->NewStringUTF("النص فارغ");
    }

    // ═══ Clear KV cache + reset sampler ═══
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_sampler_reset(g_sampler);

    // ═══ Tokenize ═══
    // Official way: first call returns NEGATIVE count
    int32_t n_prompt = -llama_tokenize(
        g_vocab,
        prompt.c_str(),
        (int32_t)prompt.size(),
        nullptr,
        0,
        true,
        true
    );

    LOGI("Required tokens: %d", n_prompt);

    if (n_prompt <= 0) {
        LOGE("Tokenize count failed: %d", n_prompt);
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    // Check context size
    int32_t n_ctx = (int32_t)llama_n_ctx(g_ctx);
    if (n_prompt >= n_ctx) {
        LOGE("Prompt too long: %d >= ctx %d", n_prompt, n_ctx);
        return env->NewStringUTF("النص أطول من سعة الذاكرة");
    }

    // Fill tokens
    std::vector<llama_token> tokens(n_prompt);
    int32_t tokenized = llama_tokenize(
        g_vocab,
        prompt.c_str(),
        (int32_t)prompt.size(),
        tokens.data(),
        n_prompt,
        true,
        true
    );

    if (tokenized < 0) {
        LOGE("Tokenize fill failed: %d", tokenized);
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    if (tokenized != n_prompt) {
        LOGI("Token count adjusted: %d -> %d", n_prompt, tokenized);
        tokens.resize(tokenized);
        n_prompt = tokenized;
    }

    LOGI("Tokenized OK: %d tokens", n_prompt);

    // Print first few tokens for debug
    for (int i = 0; i < std::min(n_prompt, 5); i++) {
        LOGI("  tok[%d] = %d", i, (int)tokens[i]);
    }

    // ═══ Decode prompt ═══
    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    LOGI("Decoding prompt...");

    int32_t dr = llama_decode(g_ctx, batch);
    LOGI("Prompt decode result: %d", dr);

    if (dr != 0) {
        LOGE("Prompt decode FAILED: %d", dr);
        return env->NewStringUTF("خطأ في معالجة النص");
    }
    LOGI("Prompt decoded OK");

    // ═══ Generate ═══
    int32_t limit = std::max(1, std::min((int32_t)maxTokens, n_ctx - n_prompt - 1));
    LOGI("Generate limit: %d tokens", limit);

    std::string output;
    output.reserve(limit * 4);

    for (int32_t i = 0; i < limit; i++) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, tok)) {
            LOGI("EOG at token %d", i);
            break;
        }

        char buf[512];
        int32_t len = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
        if (len < 0) {
            LOGE("token_to_piece fail: tok=%d err=%d", (int)tok, len);
            break;
        }
        if (len > 0) {
            output.append(buf, len);
        }

        // Next token
        batch = llama_batch_get_one(&tok, 1);
        dr = llama_decode(g_ctx, batch);
        if (dr != 0) {
            LOGE("Gen decode fail at %d: %d", i, dr);
            break;
        }

        if ((i + 1) % 10 == 0) {
            LOGI("..%d tokens generated", i + 1);
        }
    }

    LOGI("DONE: %zu chars", output.size());

    if (output.empty()) {
        return env->NewStringUTF("النموذج لم يُنتج رداً");
    }
    return env->NewStringUTF(output.c_str());
}

// ═══════════════════════════════════════════
// nativeGetMemUsage()
// ═══════════════════════════════════════════
extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(JNIEnv *, jobject) {
    return g_ctx ? (jlong)llama_state_get_size(g_ctx) : 0;
}

// ═══════════════════════════════════════════
// nativeUnload()
// ═══════════════════════════════════════════
extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_vocab = nullptr;
    llama_backend_free();
    LOGI("UNLOADED");
}
