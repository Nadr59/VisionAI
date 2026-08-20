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
    JNIEnv *env, jobject /* thiz */,
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

    LOGI("Loading model file...");
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    LOGI("Model loaded OK");

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) {
        LOGE("Failed to get vocab");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("Vocab obtained OK");

    auto cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)context_size;
    cparams.n_batch = 512;
    cparams.n_threads = (uint32_t)threads;
    cparams.n_threads_batch = (uint32_t)threads;

    LOGI("Creating context...");
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }

    g_is_loaded = true;
    LOGI("=== Model + Context + Vocab ready ===");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject /* thiz */,
    jstring jprompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k,
    jobject /* callback */)
{
    if (!g_is_loaded || !g_ctx || !g_model || !g_vocab) {
        LOGE("Not loaded: ctx=%d model=%d vocab=%d", g_ctx!=nullptr, g_model!=nullptr, g_vocab!=nullptr);
        return env->NewStringUTF("النموذج غير محمّل");
    }

    const char *prompt_cstr = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jprompt, prompt_cstr);
    LOGI("Generate: prompt=%d chars", (int)prompt.size());

    // ═══ Tokenize ═══
    LOGI("Tokenizing...");
    std::vector<llama_token> tokens(prompt.size() + 32);
    int n_tokens = llama_tokenize(
        g_vocab,
        prompt.c_str(), (int)prompt.size(),
        tokens.data(), (int)tokens.size(),
        true,   // add_special
        false   // parse_special
    );

    if (n_tokens < 0) {
        LOGI("Need more space: %d", -n_tokens);
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            g_vocab,
            prompt.c_str(), (int)prompt.size(),
            tokens.data(), (int)tokens.size(),
            true, false
        );
    }

    if (n_tokens <= 0) {
        LOGE("Tokenization failed: %d", n_tokens);
        return env->NewStringUTF("خطأ في تحليل النص");
    }
    LOGI("Tokenized: %d tokens", n_tokens);

    // ═══ Create batch ═══
    LOGI("Creating batch...");
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);

    for (int i = 0; i < n_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n_tokens - 1) ? 1 : 0;
    }
    batch.n_tokens = n_tokens;

    // ═══ Decode prompt ═══
    LOGI("Decoding prompt...");
    int ret = llama_decode(g_ctx, batch);
    if (ret != 0) {
        LOGE("Decode failed: %d", ret);
        llama_batch_free(batch);
        return env->NewStringUTF("خطأ في معالجة النص");
    }
    LOGI("Prompt decoded OK");

    // ═══ Sampler ═══
    LOGI("Creating sampler...");
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);

    if (temperature > 0.01f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));
    LOGI("Sampler ready");

    // ═══ Generate ═══
    LOGI("Starting generation loop...");
    std::string result;
    int n_generated = 0;

    for (int i = 0; i < (int)max_tokens; i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, tok)) {
            LOGI("EOS at token %d", n_generated);
            break;
        }

        char buf[256];
        int len = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) {
            result.append(buf, len);
            n_generated++;
        }

        // Prepare next
        batch.n_tokens = 0;
        batch.token[0] = tok;
        batch.pos[0] = n_tokens + n_generated - 1;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        ret = llama_decode(g_ctx, batch);
        if (ret != 0) {
            LOGE("Decode failed at step %d: %d", i, ret);
            break;
        }

        if (n_generated % 10 == 0) {
            LOGI("Generated %d tokens so far...", n_generated);
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    LOGI("=== Done: %d tokens, %d chars ===", n_generated, (int)result.size());

    if (result.empty()) {
        return env->NewStringUTF("النموذج لم يُنتج رداً");
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(
    JNIEnv * /* env */, jobject /* thiz */)
{
    if (!g_ctx) return 0;
    return (jlong)llama_state_get_size(g_ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(
    JNIEnv * /* env */, jobject /* thiz */)
{
    LOGI("Unloading...");
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_is_loaded = false;
    LOGI("Unloaded OK");
}
