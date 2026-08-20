#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.cpp/include/llama.h"
#include "llama.cpp/common/common.h"

#define TAG "VisionAI_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static bool g_is_loaded = false;

// ═══ nativeLoadModel ═══
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv *env, jobject thiz,
    jstring jpath, jint context_size, jint threads)
{
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("nativeLoadModel: path=%s ctx=%d threads=%d", path, context_size, threads);

    // Unload previous
    if (g_is_loaded) {
        if (g_ctx) llama_free(g_ctx);
        if (g_model) llama_model_free(g_model);
        g_ctx = nullptr;
        g_model = nullptr;
        g_is_loaded = false;
    }

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only

    LOGI("Loading model...");
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("Failed to load model!");
        return JNI_FALSE;
    }
    LOGI("Model loaded OK");

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = context_size;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    LOGI("Creating context...");
    g_ctx = llama_init_from_model(g_model, ctx_params);

    if (!g_ctx) {
        LOGE("Failed to create context!");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_is_loaded = true;
    LOGI("Model and context ready!");
    return JNI_TRUE;
}

// ═══ nativeGenerate ═══
extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject thiz,
    jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k,
    jobject callback)
{
    if (!g_is_loaded || !g_ctx || !g_model) {
        LOGE("Model not loaded!");
        return env->NewStringUTF("النموذج غير محمّل");
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    LOGI("Generate: prompt=%d chars, maxTokens=%d", (int)strlen(prompt), max_tokens);

    // Tokenize
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int n_prompt = -llama_tokenize(vocab, prompt, strlen(prompt), nullptr, 0, true, true);
    std::vector<llama_token> prompt_tokens(n_prompt);
    llama_tokenize(vocab, prompt, strlen(prompt), prompt_tokens.data(), n_prompt, true, true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    LOGI("Prompt tokens: %d", n_prompt);

    // Create batch
    llama_batch batch = llama_batch_init(n_prompt, 0, 1);

    // Evaluate prompt
    for (int i = 0; i < n_prompt; i++) {
        batch.token[batch.n_tokens] = prompt_tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_tokens = 1;
        batch.logits[i == n_prompt - 1] = 1;
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Failed to decode prompt at token %d", i);
            llama_batch_free(batch);
            return env->NewStringUTF("خطأ في معالجة النص");
        }
    }

    // Sampler
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature > 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    // Generate tokens
    std::string result;
    int n_generated = 0;
    llama_token new_token;

    for (int i = 0; i < max_tokens; i++) {
        new_token = llama_sampler_sample(sampler, g_ctx, -1);

        // Check EOS
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("EOS reached at token %d", n_generated);
            break;
        }

        // Convert token to text
        char buf[256];
        int len = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
            n_generated++;
        }

        // Prepare next batch
        batch.n_tokens = 0;
        batch.token[batch.n_tokens] = new_token;
        batch.pos[batch.n_tokens] = n_prompt + n_generated - 1;
        batch.n_tokens = 1;
        batch.logits[0] = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at generation step %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    LOGI("Generated %d tokens, result length=%d", n_generated, (int)result.size());
    return env->NewStringUTF(result.c_str());
}

// ═══ nativeGetMemUsage ═══
extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(
    JNIEnv *env, jobject thiz)
{
    if (!g_ctx) return 0;
    jlong mem = llama_get_state_size(g_ctx);
    LOGI("Memory usage: %lld bytes", mem);
    return mem;
}

// ═══ nativeUnload ═══
extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(
    JNIEnv *env, jobject thiz)
{
    LOGI("Unloading model...");
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_is_loaded = false;
    LOGI("Model unloaded");
}
