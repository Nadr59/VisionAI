#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define TAG "VisionAI_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static const llama_vocab *g_vocab = nullptr;
static bool g_is_loaded = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv *env, jobject,
    jstring jpath, jint context_size, jint threads)
{
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("LoadModel: %s ctx=%d threads=%d", path, context_size, threads);

    if (g_is_loaded) {
        if (g_ctx) llama_free(g_ctx);
        if (g_model) llama_model_free(g_model);
        g_ctx = nullptr; g_model = nullptr; g_vocab = nullptr;
        g_is_loaded = false;
    }

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) { LOGE("Model load failed"); return JNI_FALSE; }
    LOGI("Model OK");

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) { LOGE("No vocab"); llama_model_free(g_model); g_model = nullptr; return JNI_FALSE; }

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)context_size;
    cp.n_batch = (uint32_t)context_size;
    cp.n_threads = (uint32_t)threads;
    cp.n_threads_batch = (uint32_t)threads;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("Context failed"); llama_model_free(g_model); g_model = nullptr; g_vocab = nullptr; return JNI_FALSE; }

    g_is_loaded = true;
    LOGI("All ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject,
    jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k,
    jobject)
{
    if (!g_is_loaded || !g_ctx || !g_model || !g_vocab) {
        return env->NewStringUTF("النموذج غير محمّل");
    }

    // Get prompt
    const char *raw = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(raw);
    env->ReleaseStringUTFChars(jprompt, raw);
    LOGI("Prompt: %d chars", (int)prompt.size());

    // ═══ Step 1: Tokenize ═══
    int n_tokens = llama_tokenize(
        g_vocab,
        prompt.c_str(), (int)prompt.size(),
        nullptr, 0,
        true, false
    );

    if (n_tokens <= 0) {
        LOGE("Tokenize count: %d", n_tokens);
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    std::vector<llama_token> tokens(n_tokens);
    int ok = llama_tokenize(
        g_vocab,
        prompt.c_str(), (int)prompt.size(),
        tokens.data(), n_tokens,
        true, false
    );

    if (ok < 0) {
        LOGE("Tokenize fill: %d", ok);
        return env->NewStringUTF("خطأ في تحليل النص");
    }
    LOGI("Tokens: %d", n_tokens);

    // ═══ Step 2: Evaluate prompt — ONE batch ═══
    {
        llama_batch batch = llama_batch_init(n_tokens, 0, 1);

        for (int i = 0; i < n_tokens; i++) {
            batch.token[i] = tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == n_tokens - 1) ? 1 : 0;
        }
        batch.n_tokens = n_tokens;

        LOGI("Decoding prompt batch (%d tokens)...", n_tokens);
        int ret = llama_decode(g_ctx, batch);
        llama_batch_free(batch);

        if (ret != 0) {
            LOGE("Prompt decode failed: %d", ret);
            return env->NewStringUTF("خطأ في معالجة النص");
        }
        LOGI("Prompt decoded OK");
    }

    // ═══ Step 3: Sampler ═══
    auto sp = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sp);
    float temp = (temperature > 0.01f) ? temperature : 0.7f;
    float tp = (top_p > 0.01f) ? top_p : 0.9f;
    int tk = (top_k > 0) ? top_k : 40;

    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(tp, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(tk));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));
    LOGI("Sampler: temp=%.2f top_p=%.2f top_k=%d", temp, tp, tk);

    // ═══ Step 4: Generate tokens ═══
    std::string result;
    int generated = 0;
    int cur_pos = n_tokens;

    llama_batch gen_batch = llama_batch_init(1, 0, 1);

    for (int i = 0; i < (int)max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, tok)) {
            LOGI("EOS at %d tokens", generated);
            break;
        }

        char buf[256];
        int len = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
            generated++;
        }

        // Feed next
        gen_batch.n_tokens = 1;
        gen_batch.token[0] = tok;
        gen_batch.pos[0] = cur_pos++;
        gen_batch.n_seq_id[0] = 1;
        gen_batch.seq_id[0][0] = 0;
        gen_batch.logits[0] = 1;

        int ret = llama_decode(g_ctx, gen_batch);
        if (ret != 0) {
            LOGE("Gen decode fail at %d: %d", i, ret);
            break;
        }

        if (generated % 5 == 0 && generated > 0) {
            LOGI("...%d tokens generated", generated);
        }
    }

    llama_batch_free(gen_batch);
    llama_sampler_free(sampler);

    LOGI("Done: %d tokens, %d chars", generated, (int)result.size());

    if (result.empty()) return env->NewStringUTF("النموذج لم يُنتج رداً. جرّب نصاً أقصر.");
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(JNIEnv *, jobject) {
    if (!g_ctx) return 0;
    return (jlong)llama_state_get_size(g_ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(JNIEnv *, jobject) {
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr; g_model = nullptr; g_vocab = nullptr;
    g_is_loaded = false;
    LOGI("Unloaded");
}
