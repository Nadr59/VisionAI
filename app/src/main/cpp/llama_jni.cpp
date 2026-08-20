#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"
// common.h not needed
// No common.h needed — using llama.h directly

#define TAG "VisionAI_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static bool g_is_loaded = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv *env, jobject /* thiz */,
    jstring jpath, jint context_size, jint threads)
{
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("nativeLoadModel: path=%s ctx=%d threads=%d", path, context_size, threads);

    // Cleanup previous
    if (g_is_loaded) {
        if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
        if (g_model) { llama_model_free(g_model); g_model = nullptr; }
        g_is_loaded = false;
    }

    // Load model
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("Failed to load model file");
        return JNI_FALSE;
    }
    LOGI("Model loaded successfully");

    // Create context
    auto cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)context_size;
    cparams.n_batch = 512;
    cparams.n_threads = (uint32_t)threads;
    cparams.n_threads_batch = (uint32_t)threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_is_loaded = true;
    LOGI("Model and context ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject /* thiz */,
    jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k,
    jobject /* callback */)
{
    if (!g_is_loaded || !g_ctx || !g_model) {
        LOGE("Model not loaded");
        return env->NewStringUTF("النموذج غير محمّل");
    }

    const char *prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jprompt, prompt_cstr);

    LOGI("Generate: prompt=%d chars maxTokens=%d", (int)prompt.size(), max_tokens);

    // Get vocab
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    // Tokenize
    std::vector<llama_token> tokens(prompt.size() + 32);
    int n_tokens = llama_tokenize(
        vocab,
        prompt.c_str(), prompt.size(),
        tokens.data(), tokens.size(),
        true,  // add_special
        false  // parse_special
    );

    if (n_tokens < 0) {
        // Need more space
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            vocab,
            prompt.c_str(), prompt.size(),
            tokens.data(), tokens.size(),
            true, false
        );
    }

    if (n_tokens <= 0) {
        LOGE("Tokenization failed: %d", n_tokens);
        return env->NewStringUTF("خطأ في تحليل النص");
    }

    LOGI("Prompt tokens: %d", n_tokens);

    // Evaluate prompt
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);

    for (int i = 0; i < n_tokens; i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens] = &batch.n_seq_id[batch.n_tokens];
        batch.logits[batch.n_tokens] = (i == n_tokens - 1) ? 1 : 0;
        batch.n_tokens++;
    }

    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Failed to decode prompt batch");
        llama_batch_free(batch);
        return env->NewStringUTF("خطأ في معالجة النص");
    }

    // Setup sampler
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);

    if (temperature > 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    // Generate
    std::string result;
    int n_generated = 0;

    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, batch.n_tokens - 1);

        if (llama_vocab_is_eog(vocab, tok)) {
            LOGI("EOS at token %d", n_generated);
            break;
        }

        char buf[256];
        int len = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
            n_generated++;
        }

        // Next token
        batch.n_tokens = 0;
        batch.token[0] = tok;
        batch.pos[0] = n_tokens + n_generated - 1;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0] = &batch.n_seq_id[0];
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at step %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    LOGI("Done: %d tokens, %d chars", n_generated, (int)result.size());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(
    JNIEnv * /* env */, jobject /* thiz */)
{
    if (!g_ctx) return 0;
    return (jlong)llama_get_state_size(g_ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(
    JNIEnv * /* env */, jobject /* thiz */)
{
    LOGI("Unloading...");
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_is_loaded = false;
    LOGI("Unloaded");
}
