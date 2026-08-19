#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "common.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    bool is_loaded = false;
};

static LlamaSession g_session;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv* env, jobject /* this */,
    jstring modelPath, jint contextSize, jint threads) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s ctx=%d threads=%d", path, contextSize, threads);

    // Free previous session
    if (g_session.is_loaded) {
        if (g_session.ctx) llama_free(g_session.ctx);
        if (g_session.model) llama_model_free(g_session.model);
        g_session.is_loaded = false;
    }

    // Load model
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only for Android

    g_session.model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_session.model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Create context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = contextSize;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.flash_attn = false;

    g_session.ctx = llama_init_from_model(g_session.model, cparams);
    if (!g_session.ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_session.model);
        g_session.model = nullptr;
        return JNI_FALSE;
    }

    g_session.vocab = llama_model_get_vocab(g_session.model);
    g_session.is_loaded = true;

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv* env, jobject /* this */,
    jstring prompt, jint maxTokens, jfloat temperature,
    jfloat topP, jint topK, jobject callback) {

    if (!g_session.is_loaded) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText(promptStr);
    env->ReleaseStringUTFChars(prompt, promptStr);

    // Tokenize
    std::vector<llama_token> tokens(promptText.size() + 512);
    int n_tokens = llama_tokenize(
        g_session.vocab,
        promptText.c_str(), promptText.size(),
        tokens.data(), tokens.size(),
        true, true
    );

    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // Clear KV cache
    llama_kv_cache_clear(g_session.ctx);

    // Create batch
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n_tokens - 1) ? 1 : 0;
    }
    batch.n_tokens = n_tokens;

    // Decode prompt
    if (llama_decode(g_session.ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Sampler
    auto* sparams = new llama_sampler_chain_params;
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

    // Get callback method
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");

    std::string result;
    int n_generated = 0;
    llama_token new_token;

    while (n_generated < maxTokens) {
        new_token = llama_sampler_sample(sampler, g_session.ctx, -1);

        if (llama_vocab_is_eog(g_session.vocab, new_token)) break;

        // Get token text
        char buf[256];
        int len = llama_token_to_piece(g_session.vocab, new_token, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string tokenStr(buf, len);
            result += tokenStr;

            // Callback to Kotlin
            jstring jToken = env->NewStringUTF(tokenStr.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jToken);
            env->DeleteLocalRef(jToken);
        }

        // Prepare next batch
        llama_batch_clear(batch);
        llama_batch_add(batch, new_token, n_tokens + n_generated, {0}, true);

        if (llama_decode(g_session.ctx, batch) != 0) {
            LOGE("Decode failed at token %d", n_generated);
            break;
        }

        n_generated++;
    }

    llama_batch_free(batch);
    llama_sampler_free(sampler);
    delete sparams;

    LOGI("Generated %d tokens", n_generated);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeFreeModel(
    JNIEnv* env, jobject /* this */) {
    if (g_session.ctx) {
        llama_free(g_session.ctx);
        g_session.ctx = nullptr;
    }
    if (g_session.model) {
        llama_model_free(g_session.model);
        g_session.model = nullptr;
    }
    g_session.is_loaded = false;
    LOGI("Model freed");
}

JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeIsLoaded(
    JNIEnv* env, jobject /* this */) {
    return g_session.is_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(
    JNIEnv* env, jobject /* this */) {
    if (!g_session.ctx) return 0;
    return (jlong)llama_get_memory_size(g_session.ctx);
}

} // extern "C"
