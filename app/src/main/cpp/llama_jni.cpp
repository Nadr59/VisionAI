#include <jni.h>
#include <string>
#include <vector>
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
    LOGI("nativeLoadModel: path=%s ctx=%d threads=%d", path, context_size, threads);

    if (g_is_loaded) {
        if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
        if (g_model) { llama_model_free(g_model); g_model = nullptr; }
        g_vocab = nullptr;
        g_is_loaded = false;
    }

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    LOGI("Model loaded");

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        LOGE("No vocab");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)context_size;
    cparams.n_batch = 128;
    cparams.n_threads = (uint32_t)threads;
    cparams.n_threads_batch = (uint32_t)threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("No context");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }

    g_is_loaded = true;
    LOGI("Ready!");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject,
    jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k,
    jobject)
{
    if (!g_is_loaded) {
        return env->NewStringUTF("النموذج غير محمّل");
    }

    const char *prompt_raw = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_raw);
    env->ReleaseStringUTFChars(jprompt, prompt_raw);
    LOGI("Generate: %d chars, max=%d", (int)prompt.size(), max_tokens);

    // Tokenize
    int n = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, false);
    if (n <= 0) {
        LOGE("Tokenize count failed: %d", n);
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    std::vector<llama_token> tokens(n);
    int n_tokens = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(), tokens.data(), n, true, false);
    LOGI("Tokens: %d", n_tokens);

    // Evaluate ALL prompt tokens at once using common.h style
    // Simple loop: one token at a time
    for (int i = 0; i < n_tokens; i++) {
        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = tokens[i];
        batch.pos[0] = i;
        batch.seq_id[0] = new llama_seq_id[1];
        batch.seq_id[0][0] = 0;
        batch.n_seq_id[0] = 1;
        batch.logits[0] = (i == n_tokens - 1) ? 1 : 0;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at %d", i);
            delete[] batch.seq_id[0];
            llama_batch_free(batch);
            return env->NewStringUTF("خطأ في معالجة النص");
        }
        delete[] batch.seq_id[0];
        llama_batch_free(batch);
    }
    LOGI("Prompt evaluated OK");

    // Sampler
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature > 0.01f ? temperature : 0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p > 0.01f ? top_p : 0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

    // Generate
    std::string result;
    int generated = 0;
    int pos = n_tokens;

    for (int i = 0; i < (int)max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, tok)) {
            LOGI("EOS at %d", generated);
            break;
        }

        char buf[256];
        int len = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
            generated++;
        }

        // Feed token
        llama_batch batch = llama_batch_init(1, 0, 1);
        batch.n_tokens = 1;
        batch.token[0] = tok;
        batch.pos[0] = pos++;
        batch.seq_id[0] = new llama_seq_id[1];
        batch.seq_id[0][0] = 0;
        batch.n_seq_id[0] = 1;
        batch.logits[0] = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode fail at gen %d", i);
            delete[] batch.seq_id[0];
            llama_batch_free(batch);
            break;
        }
        delete[] batch.seq_id[0];
        llama_batch_free(batch);

        if (generated % 5 == 0) {
            LOGI("...%d tokens", generated);
        }
    }

    llama_sampler_free(sampler);
    LOGI("Done: %d tokens, %d chars", generated, (int)result.size());

    if (result.empty()) return env->NewStringUTF("النموذج لم يُنتج رداً");
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(JNIEnv *, jobject) {
    if (!g_ctx) return 0;
    return (jlong)llama_state_get_size(g_ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(JNIEnv *, jobject) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_is_loaded = false;
    LOGI("Unloaded");
}
