#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define TAG "VJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static const llama_vocab *g_vocab = nullptr;
static bool g_loaded = false;

// Helper: create batch for multiple tokens at given position
static llama_batch make_batch(const llama_token *tokens, int n, int pos_start, bool all_logits) {
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = pos_start + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = all_logits ? 1 : (i == n - 1) ? 1 : 0;
    }
    batch.n_tokens = n;
    return batch;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeLoadModel(
    JNIEnv *env, jobject, jstring jpath, jint ctx_size, jint threads)
{
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("LOAD: %s ctx=%d th=%d", path, ctx_size, threads);

    if (g_loaded) {
        if (g_ctx) llama_free(g_ctx);
        if (g_model) llama_model_free(g_model);
        g_ctx = nullptr; g_model = nullptr; g_vocab = nullptr; g_loaded = false;
    }

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) { LOGE("Model FAIL"); return JNI_FALSE; }

    g_vocab = llama_model_get_vocab(g_model);
    if (!g_vocab) { LOGE("Vocab FAIL"); llama_model_free(g_model); g_model = nullptr; return JNI_FALSE; }

    auto cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)ctx_size;
    cp.n_batch = (uint32_t)ctx_size;
    cp.n_threads = (uint32_t)threads;
    cp.n_threads_batch = (uint32_t)threads;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("Ctx FAIL"); llama_model_free(g_model); g_model = nullptr; g_vocab = nullptr; return JNI_FALSE; }

    g_loaded = true;
    LOGI("LOAD OK");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGenerate(
    JNIEnv *env, jobject, jstring jprompt, jint max_tok,
    jfloat temp, jfloat top_p, jint top_k, jobject)
{
    if (!g_loaded) return env->NewStringUTF("النموذج غير محمّل");

    const char *raw = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(raw);
    env->ReleaseStringUTFChars(jprompt, raw);
    LOGI("GEN: %d chars, max=%d", (int)prompt.size(), max_tok);

    // ═══ Tokenize ═══
    int n_tok = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, false);
    if (n_tok <= 0) { LOGE("Tok cnt: %d", n_tok); return env->NewStringUTF("خطأ في تحليل النص"); }

    std::vector<llama_token> toks(n_tok);
    n_tok = llama_tokenize(g_vocab, prompt.c_str(), (int)prompt.size(), toks.data(), n_tok, true, false);
    if (n_tok <= 0) { LOGE("Tok fill: %d", n_tok); return env->NewStringUTF("خطأ في تحليل النص"); }
    LOGI("Tokens: %d", n_tok);

    // ═══ Decode prompt ═══
    LOGI("Decoding prompt...");
    {
        llama_batch batch = make_batch(toks.data(), n_tok, 0, false);
        int r = llama_decode(g_ctx, batch);
        llama_batch_free(batch);
        if (r != 0) { LOGE("Prompt decode: %d", r); return env->NewStringUTF("خطأ في معالجة النص"); }
    }
    LOGI("Prompt OK");

    // ═══ Sampler ═══
    auto sp = llama_sampler_chain_default_params();
    llama_sampler *smplr = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smplr, llama_sampler_init_temp(temp > 0.01f ? temp : 0.7f));
    llama_sampler_chain_add(smplr, llama_sampler_init_top_p(top_p > 0.01f ? top_p : 0.9f, 1));
    llama_sampler_chain_add(smplr, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
    llama_sampler_chain_add(smplr, llama_sampler_init_dist(0));

    // ═══ Generate ═══
    std::string result;
    int gen = 0;
    int pos = n_tok;

    for (int i = 0; i < (int)max_tok; i++) {
        llama_token tok = llama_sampler_sample(smplr, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, tok)) { LOGI("EOS@%d", gen); break; }

        char buf[256];
        int len = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
        if (len > 0) { result.append(buf, len); gen++; }

        // Feed next token
        llama_batch batch = make_batch(&tok, 1, pos, true);
        pos++;
        int r = llama_decode(g_ctx, batch);
        llama_batch_free(batch);
        if (r != 0) { LOGE("Gen@%d: %d", i, r); break; }

        if (gen % 10 == 0 && gen > 0) LOGI("..%d", gen);
    }

    llama_sampler_free(smplr);
    LOGI("DONE: %d tok, %d chr", gen, (int)result.size());
    if (result.empty()) return env->NewStringUTF("النموذج لم يُنتج رداً");
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeGetMemUsage(JNIEnv *, jobject) {
    return g_ctx ? (jlong)llama_state_get_size(g_ctx) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nadrlab_visionai_ai_LocalLlmManager_nativeUnload(JNIEnv *, jobject) {
    if (g_ctx) llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr; g_model = nullptr; g_vocab = nullptr; g_loaded = false;
    LOGI("UNLOADED");
}
