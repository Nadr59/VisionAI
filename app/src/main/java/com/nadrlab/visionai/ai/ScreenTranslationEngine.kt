package com.nadrlab.visionai.ai

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.nadrlab.visionai.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Screen translation backends. Calls run on Dispatchers.IO. */
object ScreenTranslationEngine {

    private val translators = LinkedHashMap<String, Translator>()
    private val languageIdentifier by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.5f)
                .build()
        )
    }

    suspend fun translateBatch(
        texts: List<String>,
        settings: AppSettings
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        when (settings.screenTranslationEngine) {
            ENGINE_CLOUD -> translateWithCloud(texts, settings.screenTranslationTarget, settings)
            else -> translateWithMlKit(texts, settings.screenTranslationTarget)
        }
    }

    private fun translateWithMlKit(texts: List<String>, targetTag: String): List<String> {
        val target = TranslateLanguage.fromLanguageTag(targetTag)
            ?: throw IllegalArgumentException("لغة الهدف غير مدعومة في ML Kit: $targetTag")

        val output = texts.toMutableList()
        val groups = LinkedHashMap<String, MutableList<Int>>()

        texts.forEachIndexed { index, text ->
            val source = try {
                Tasks.await(languageIdentifier.identifyLanguage(text))
            } catch (_: Exception) {
                "und"
            }
            if (source != "und" && source != targetTag) {
                groups.getOrPut(source) { mutableListOf() }.add(index)
            }
        }

        groups.forEach { (sourceTag, indexes) ->
            val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: return@forEach
            val translator = getTranslator(source, target)
            Tasks.await(
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            )
            indexes.forEach { index ->
                output[index] = Tasks.await(translator.translate(texts[index]))
            }
        }

        return output
    }

    @Synchronized
    private fun getTranslator(source: String, target: String): Translator {
        val key = "$source>$target"
        translators[key]?.let { return it }

        if (translators.size >= MAX_TRANSLATORS) {
            val oldestKey = translators.keys.firstOrNull()
            if (oldestKey != null) translators.remove(oldestKey)?.close()
        }

        return Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        ).also { translators[key] = it }
    }

    private suspend fun translateWithCloud(
        texts: List<String>,
        targetTag: String,
        settings: AppSettings
    ): List<String> {
        CloudVisionManager.init(settings)

        val payload = JSONArray(texts).toString()
        val targetName = when (targetTag) {
            "ar" -> "العربية"
            "en" -> "الإنجليزية"
            "fr" -> "الفرنسية"
            "de" -> "الألمانية"
            "es" -> "الإسبانية"
            "tr" -> "التركية"
            "fa" -> "الفارسية"
            "ur" -> "الأردية"
            else -> targetTag
        }
        val prompt = """
            أنت محرك ترجمة. ترجم كل عنصر في مصفوفة JSON التالية إلى $targetName.
            حافظ على ترتيب العناصر، ولا تحذف أو تدمج أي عنصر.
            أعد مصفوفة JSON صحيحة فقط، تحتوي سلسلة مترجمة واحدة مقابل كل عنصر.
            لا تضف شرحاً أو Markdown أو علامات code fence.
            النصوص: $payload
        """.trimIndent()

        val response = CloudVisionManager.analyzeText(prompt).getOrElse { throw it }
        val start = response.indexOf('[')
        val end = response.lastIndexOf(']')
        if (start < 0 || end <= start) {
            throw IllegalStateException("استجابة المحرك السحابي ليست مصفوفة ترجمة صالحة")
        }

        val array = JSONArray(response.substring(start, end + 1))
        if (array.length() != texts.size) {
            throw IllegalStateException("عدد الترجمات لا يطابق عدد النصوص المرسلة")
        }

        return (0 until array.length()).map { index ->
            array.optString(index).ifBlank { texts[index] }
        }
    }

    @Synchronized
    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private const val ENGINE_CLOUD = "CLOUD"
    private const val MAX_TRANSLATORS = 3
}
