package com.google.ai.edge.gallery.ui.scraper

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import com.google.firebase.storage.StorageException
import com.google.ai.edge.gallery.infrastructure.FirebaseSync
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

data class TenderFolder(
    val tenderId: String,
    val files: List<File>
)

data class ScraperUiState(
    val isScraping: Boolean = false,
    val downloadedTenders: List<TenderFolder> = emptyList(),
    val gemmaReadCheckStatusByTender: Map<String, String> = emptyMap(),
    val gemmaReadCheckResultByTender: Map<String, String> = emptyMap(),
    val gemmaEnrichmentStatusByTender: Map<String, String> = emptyMap(),
    val firebaseUploadStatusByTender: Map<String, String> = emptyMap()
)

@HiltViewModel
class TenderScraperViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "AGTenderScraperVM"
        private const val GEMMA_READ_CHECK_FILENAME = "gemma-read-check.txt"
        private const val GEMMA_ENRICHMENT_FILENAME = "gemma-manifest-enrichment.json"
        private const val MAX_FILE_TEXT_CHARS = 12000
        private const val MAX_READ_CHECK_PROMPT_CHARS = 24000
        private const val MAX_ENRICHMENT_PREP_PROMPT_CHARS = 1800
        private const val MAX_CORE_DOCUMENT_CHARS = 1800
        private const val MAX_REQUIREMENTS_DOCUMENT_CHARS = 2600
        private const val MAX_BOQ_DOCUMENT_CHARS = 2600
    }

    private val fileManager = TenderFileManager(context)
    private val scraper = TenderScraper(context, fileManager)
    private val firebaseSync = FirebaseSync(context)

    private val _uiState = MutableStateFlow(ScraperUiState())
    val uiState: StateFlow<ScraperUiState> = _uiState

    fun startScraping(limit: Int) {
        Log.d("ScraperDebug", "Button clicked, starting scrape for $limit tenders...")
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("ScraperDebug", "Setting isScraping to true")
            resetProcessingStatus(isScraping = true)
            try {
                // Note: Assuming scrapeTenderList is modified to take limit
                scraper.fetchLatestTenders(limit)
            } finally {
                Log.d("ScraperDebug", "Setting isScraping to false")
                delay(2000) // Keep progress visible for 2 seconds
                _uiState.value = _uiState.value.copy(isScraping = false)
                loadDownloadedTenders()
            }
        }
    }

    fun scrapeEnrichAndUploadLatest(model: Model, limit: Int) {
        Log.d(TAG, "Starting automated scrape/enrich/upload for $limit tenders")
        viewModelScope.launch(Dispatchers.IO) {
            resetProcessingStatus(isScraping = true)
            try {
                scraper.fetchLatestTenders(limit)
                loadDownloadedTenders()

                val tenderIds = _uiState.value.downloadedTenders.map { it.tenderId }
                if (tenderIds.isEmpty()) {
                    Log.w(TAG, "Automation found no downloaded tenders after scrape")
                    return@launch
                }

                tenderIds.forEachIndexed { index, tenderId ->
                    val position = index + 1
                    updateGemmaEnrichmentStatus(
                        tenderId,
                        "Automation $position/${tenderIds.size}: starting Gemma enrichment...",
                    )
                    val enriched = enrichManifestWithGemmaInternal(model, tenderId)
                    if (!enriched) {
                        updateFirebaseUploadStatus(
                            tenderId,
                            "Skipped Firebase upload because Gemma enrichment failed.",
                        )
                        return@forEachIndexed
                    }

                    updateFirebaseUploadStatus(
                        tenderId,
                        "Automation $position/${tenderIds.size}: starting Firebase upload...",
                    )
                    uploadTenderToFirebaseInternal(tenderId)
                }
            } finally {
                delay(2000)
                _uiState.value = _uiState.value.copy(isScraping = false)
                loadDownloadedTenders()
            }
        }
    }

    fun loadDownloadedTenders() {
        val tendersDir = File(context.getExternalFilesDir(null), "tenders")
        val tenderFolders = tendersDir.listFiles { file -> file.isDirectory }?.map { dir ->
            TenderFolder(
                tenderId = dir.name,
                files = dir.listFiles()?.toList() ?: emptyList()
            )
        } ?: emptyList()
        _uiState.value = _uiState.value.copy(downloadedTenders = tenderFolders)
    }

    fun getManifestContent(tenderId: String): String {
        val folder = fileManager.getTenderFolder(tenderId)
        val manifestFile = File(folder, "manifest.json")
        return try {
            manifestFile.readText()
        } catch (e: Exception) {
            "Error reading manifest: ${e.message}"
        }
    }

    fun getTenderFiles(tenderId: String): List<File> {
        val folder = fileManager.getTenderFolder(tenderId)
        return folder.listFiles()?.toList() ?: emptyList()
    }

    fun getGemmaReadCheckContent(tenderId: String): String {
        val folder = fileManager.getTenderFolder(tenderId)
        val resultFile = File(folder, GEMMA_READ_CHECK_FILENAME)
        return try {
            resultFile.readText()
        } catch (e: Exception) {
            uiState.value.gemmaReadCheckResultByTender[tenderId]
                ?: "No Gemma read check has been run for this tender yet."
        }
    }

    fun runGemmaReadCheck(model: Model, tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val prepared =
                prepareTenderDocuments(
                    model = model,
                    tenderId = tenderId,
                    statusUpdater = ::updateGemmaStatus,
                    resultUpdater = ::updateGemmaResult,
                    maxPromptChars = MAX_READ_CHECK_PROMPT_CHARS,
                )
                ?: return@launch

            runGemmaInference(
                model = model,
                tenderId = tenderId,
                prompt = buildGemmaReadCheckPrompt(tenderId = tenderId, documentBundle = prepared.documentBundle),
                onPartial = { response -> updateGemmaResult(tenderId, response) },
                onDone = { finalResponse ->
                    fileManager.saveTextFile(prepared.folder, GEMMA_READ_CHECK_FILENAME, finalResponse)
                    updateGemmaResult(tenderId, finalResponse)
                    updateGemmaStatus(tenderId, "Gemma read check completed.")
                },
                onStopped = { updateGemmaStatus(tenderId, "Gemma read check stopped.") },
                onError = { error ->
                    Log.e(TAG, "Gemma read check failed for $tenderId: $error")
                    updateGemmaStatus(tenderId, error)
                    updateGemmaResult(tenderId, "Gemma read check failed: $error")
                },
                statusUpdater = ::updateGemmaStatus,
                readableFileCount = prepared.readableFiles.size,
            )
        }
    }

    fun uploadTenderToFirebase(tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            uploadTenderToFirebaseInternal(tenderId)
        }
    }

    fun enrichManifestWithGemma(model: Model, tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            enrichManifestWithGemmaInternal(model, tenderId)
        }
    }

    private suspend fun enrichManifestWithGemmaInternal(model: Model, tenderId: String): Boolean {
            val prepared =
                prepareTenderDocuments(
                    model = model,
                    tenderId = tenderId,
                    statusUpdater = ::updateGemmaEnrichmentStatus,
                    resultUpdater = null,
                    maxPromptChars = MAX_ENRICHMENT_PREP_PROMPT_CHARS,
                )
                ?: return false

            val manifestContext = buildManifestContext(prepared.folder)
            val combinedEnrichment = JSONObject()

            try {
                updateGemmaEnrichmentStatus(tenderId, "Extracting core tender details with Gemma...")
                val coreResponse =
                    runGemmaInferenceForResult(
                        model = model,
                        tenderId = tenderId,
                        prompt =
                            buildGemmaCoreDetailsPrompt(
                                tenderId = tenderId,
                                manifestContext = manifestContext,
                                documentBundle =
                                    buildDocumentBundle(
                                        prepared.readableFiles,
                                        MAX_CORE_DOCUMENT_CHARS,
                                    ),
                            ),
                        runningStatus = "Gemma is extracting core tender details...",
                        statusUpdater = ::updateGemmaEnrichmentStatus,
                    )
                val coreJson = extractJsonObject(coreResponse)
                combinedEnrichment.put("documentType", coreJson.optString("documentType", "unknown"))
                combinedEnrichment.put("briefDescription", coreJson.optString("briefDescription", ""))
                combinedEnrichment.put("industry", extractIndustryValue(coreJson))
                combinedEnrichment.put("beeLevel", extractBeeLevelValue(coreJson))
                combinedEnrichment.put(
                    "estimatedTenderValue",
                    coreJson.optJSONObject("estimatedTenderValue") ?: JSONObject.NULL,
                )
                combinedEnrichment.put(
                    "completeTenderDescription",
                    coreJson.optString("completeTenderDescription", ""),
                )

                updateGemmaEnrichmentStatus(tenderId, "Extracting requirements with Gemma...")
                val requirementsResponse =
                    runGemmaInferenceForResult(
                        model = model,
                        tenderId = tenderId,
                        prompt =
                            buildGemmaRequirementsPrompt(
                                tenderId = tenderId,
                                manifestContext = manifestContext,
                                documentBundle =
                                    buildDocumentBundle(
                                        prepared.readableFiles,
                                        MAX_REQUIREMENTS_DOCUMENT_CHARS,
                                    ),
                            ),
                        runningStatus = "Gemma is extracting tender requirements...",
                        statusUpdater = ::updateGemmaEnrichmentStatus,
                    )
                val requirementsJson = extractJsonObject(requirementsResponse)
                combinedEnrichment.put(
                    "requirements",
                    requirementsJson.optJSONArray("requirements") ?: JSONArray(),
                )

                updateGemmaEnrichmentStatus(tenderId, "Extracting bill of quantities with Gemma...")
                val boqResponse =
                    runGemmaInferenceForResult(
                        model = model,
                        tenderId = tenderId,
                        prompt =
                            buildGemmaBoqPrompt(
                                tenderId = tenderId,
                                manifestContext = manifestContext,
                                documentBundle =
                                    buildDocumentBundle(
                                        prepared.readableFiles,
                                        MAX_BOQ_DOCUMENT_CHARS,
                                    ),
                            ),
                        runningStatus = "Gemma is extracting bill of quantities...",
                        statusUpdater = ::updateGemmaEnrichmentStatus,
                    )
                val boqJson = extractJsonObject(boqResponse)
                combinedEnrichment.put(
                    "billOfQuantities",
                    boqJson.optJSONArray("billOfQuantities") ?: JSONArray(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed during Gemma multi-pass enrichment for $tenderId", e)
                updateGemmaEnrichmentStatus(
                    tenderId,
                    "Gemma enrichment failed: ${e.message ?: "unknown error"}",
                )
                return false
            }

            try {
                fileManager.saveTextFile(prepared.folder, GEMMA_ENRICHMENT_FILENAME, combinedEnrichment.toString(2))
                mergeGemmaEnrichmentIntoManifest(prepared.folder, combinedEnrichment)
                updateGemmaEnrichmentStatus(tenderId, "Gemma manifest enrichment completed.")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to merge Gemma enrichment into manifest for $tenderId", e)
                fileManager.saveTextFile(prepared.folder, GEMMA_ENRICHMENT_FILENAME, combinedEnrichment.toString(2))
                updateGemmaEnrichmentStatus(
                    tenderId,
                    "Gemma manifest merge failed: ${e.message ?: "unknown error"}",
                )
                return false
            }
    }

    private suspend fun uploadTenderToFirebaseInternal(tenderId: String): Boolean {
        val folder = fileManager.getTenderFolder(tenderId)
        val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            updateFirebaseUploadStatus(tenderId, "No tender files found to upload.")
            return false
        }

        updateFirebaseUploadStatus(tenderId, "Uploading ${files.size} file(s) to Firebase...")

        return try {
            val result = firebaseSync.uploadTenderFolder(folder)
            updateFirebaseUploadStatus(
                tenderId,
                "Uploaded ${result.uploadedPaths.size} file(s) to ${result.uploadedPaths.firstOrNull()?.substringBeforeLast('/') ?: "/tenders/$tenderId"}",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase upload failed for $tenderId", e)
            val message =
                when (e) {
                    is StorageException -> {
                        if (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED) {
                            "Firebase upload blocked: Storage returned 403 Permission denied for /tenders/$tenderId. Update Storage rules or use a trusted backend/service account upload path."
                        } else {
                            "Firebase upload failed: ${e.message ?: "storage error"}"
                        }
                    }
                    else -> "Firebase upload failed: ${e.message ?: "unknown error"}"
                }
            updateFirebaseUploadStatus(
                tenderId,
                message,
            )
            false
        }
    }

    private data class PreparedTenderDocuments(
        val folder: File,
        val readableFiles: List<File>,
        val documentBundle: String,
    )

    private fun prepareTenderDocuments(
        model: Model,
        tenderId: String,
        statusUpdater: (String, String) -> Unit,
        resultUpdater: ((String, String) -> Unit)?,
        maxPromptChars: Int,
    ): PreparedTenderDocuments? {
        statusUpdater(tenderId, "Preparing documents for ${model.displayName.ifEmpty { model.name }}...")

        val folder = fileManager.getTenderFolder(tenderId)
        val readableFiles =
            folder.listFiles()
                ?.filter { it.isFile }
                ?.filterNot { it.name.equals("manifest.json", ignoreCase = true) }
                ?.filterNot { it.name.equals("support-documents.json", ignoreCase = true) }
                ?.filter { isGemmaReadableFile(it) }
                .orEmpty()

        if (readableFiles.isEmpty()) {
            val message = "No readable downloaded documents found for Gemma."
            statusUpdater(tenderId, message)
            resultUpdater?.invoke(tenderId, message)
            return null
        }

        val documentBundle = buildDocumentBundle(readableFiles, maxPromptChars)
        if (documentBundle.isBlank()) {
            val message = "Downloaded documents were found, but no text could be extracted."
            statusUpdater(tenderId, message)
            resultUpdater?.invoke(tenderId, message)
            return null
        }

        statusUpdater(tenderId, "Initializing ${model.displayName.ifEmpty { model.name }}...")
        val initialized = ensureModelInitialized(model)
        if (!initialized) {
            statusUpdater(tenderId, "Failed to initialize ${model.displayName.ifEmpty { model.name }}.")
            return null
        }

        return PreparedTenderDocuments(folder = folder, readableFiles = readableFiles, documentBundle = documentBundle)
    }

    private fun runGemmaInference(
        model: Model,
        tenderId: String,
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        statusUpdater: (String, String) -> Unit,
        readableFileCount: Int,
    ) {
        var response = ""
        var firstPartial = true

        Log.d(TAG, "Gemma prompt length for $tenderId: ${prompt.length} chars")

        statusUpdater(
            tenderId,
            "Reading ${readableFileCount} downloaded document(s) with ${model.displayName.ifEmpty { model.name }}..."
        )
        model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
        Thread.sleep(300)
        model.runtimeHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { partialResult, done, _ ->
                if (firstPartial && partialResult.isNotBlank()) {
                    firstPartial = false
                    statusUpdater(tenderId, "Gemma is responding...")
                }

                if (partialResult.isNotBlank()) {
                    response = processLlmResponse("$response$partialResult")
                    onPartial(response)
                }

                if (done) {
                    onDone(response.ifBlank { "Gemma completed without returning any text." })
                }
            },
            cleanUpListener = { onStopped() },
            onError = { error -> onError(error) },
            coroutineScope = viewModelScope,
        )
    }

    private suspend fun runGemmaInferenceForResult(
        model: Model,
        tenderId: String,
        prompt: String,
        runningStatus: String,
        statusUpdater: (String, String) -> Unit,
    ): String {
        val deferred = CompletableDeferred<String>()
        var response = ""
        var firstPartial = true

        Log.d(TAG, "Gemma prompt length for $tenderId: ${prompt.length} chars")
        statusUpdater(tenderId, runningStatus)
        model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
        delay(300)
        model.runtimeHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { partialResult, done, _ ->
                if (firstPartial && partialResult.isNotBlank()) {
                    firstPartial = false
                    statusUpdater(tenderId, "Gemma is responding...")
                }

                if (partialResult.isNotBlank()) {
                    response = processLlmResponse("$response$partialResult")
                }

                if (done && !deferred.isCompleted) {
                    deferred.complete(response.ifBlank { "Gemma completed without returning any text." })
                }
            },
            cleanUpListener = {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(
                        IllegalStateException("Gemma inference stopped before completion."),
                    )
                }
            },
            onError = { error ->
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException(error))
                }
            },
            coroutineScope = viewModelScope,
        )

        return deferred.await()
    }

    private fun ensureModelInitialized(model: Model): Boolean {
        if (model.instance != null) {
            return true
        }

        var initializationError = ""
        model.runtimeHelper.initialize(
            context = context,
            model = model,
            supportImage = false,
            supportAudio = false,
            onDone = { error -> initializationError = error },
            coroutineScope = viewModelScope,
        )

        val deadline = System.currentTimeMillis() + 30000L
        while (model.instance == null && initializationError.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }

        return model.instance != null && initializationError.isEmpty()
    }

    private fun buildDocumentBundle(files: List<File>, maxPromptChars: Int): String {
        val chunks = mutableListOf<String>()
        var totalChars = 0

        for (file in files) {
            val extractedText = extractTextForGemma(file)
            if (extractedText.isBlank()) {
                continue
            }

            val header = "FILE: ${file.name}\n"
            val remainingChars = maxPromptChars - totalChars - header.length
            if (remainingChars <= 0) {
                break
            }

            val normalizedText = extractedText.take(MAX_FILE_TEXT_CHARS).trim()
            val fittedText = normalizedText.take(remainingChars)
            if (fittedText.isBlank()) {
                continue
            }

            val chunk = (header + fittedText).trimEnd()

            chunks.add(chunk)
            totalChars += chunk.length
        }

        return chunks.joinToString(separator = "\n\n---\n\n")
    }

    private fun extractTextForGemma(file: File): String {
        return try {
            when (file.extension.lowercase()) {
                "pdf" -> scraper.extractText(file)
                "txt", "md", "csv" -> file.readText()
                else -> ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text from ${file.name}", e)
            ""
        }
    }

    private fun isGemmaReadableFile(file: File): Boolean {
        return when (file.extension.lowercase()) {
            "pdf", "txt", "md", "csv" -> true
            else -> false
        }
    }

    private fun buildGemmaReadCheckPrompt(tenderId: String, documentBundle: String): String {
        return """
            You are validating whether a local Gemma model can read downloaded tender documents.

            Read the supplied tender documents for tender ${tenderId} and return a compact plain-text report with exactly these sections:
            READABLE: yes or no
            FILES_READ: comma-separated file names you were able to use
            SUMMARY: 2-4 sentences summarizing the tender documents
            EVIDENCE: 2-4 bullet points quoting or paraphrasing specific details from the documents

            If the text is incomplete, noisy, or unreadable, say so explicitly.
            Do not return markdown code fences.

            DOCUMENTS:
            ${documentBundle}
        """.trimIndent()
    }

    private fun buildManifestContext(folder: File): String {
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        return """
            TENDER METADATA:
            tender_No: ${manifest.optString("tender_No", "")}
            description: ${manifest.optString("description", "")}
            type: ${manifest.optString("type", "")}
            organ_of_State: ${manifest.optString("organ_of_State", "")}
            province: ${manifest.optString("province", "")}
            closing_Date: ${manifest.optString("closing_Date", "")}
            delivery: ${manifest.optString("delivery", "")}
        """.trimIndent()
    }

    private fun buildGemmaCoreDetailsPrompt(
        tenderId: String,
        manifestContext: String,
        documentBundle: String,
    ): String {
        return """
            You are extracting core tender details from tender documents.

            Return exactly one valid JSON object and nothing else.

            Schema:
            {
              "documentType": "tender|advert|mixed|unknown",
              "briefDescription": "short plain-English summary",
                            "industry": "one of: Information Technology & Telecommunications | Construction & Civil Engineering | Medical & Health Services | Security & Guarding Services | Professional & Consulting Services | Agriculture, Forestry & Fishing | Manufacturing & Industrial | Energy, Water & Waste Management | Transport, Storage & Logistics | Education & Training | Media, Advertising & Marketing | Tourism, Hospitality & Catering | Legal | unknown",
                            "beeLevel": "B-BBEE level text such as Level 1, Level 2, exempted micro enterprise, QSE, non-compliant, or unknown",
              "estimatedTenderValue": {
                "amount": number or null,
                "currency": "ZAR or other currency code or null",
                "displayValue": "original value text if present, else null",
                "confidence": "high|medium|low"
              },
                            "completeTenderDescription": "full detailed description based on the documents"
            }

            Rules:
            - Keep all text compact and evidence-based.
            - If a field is missing, use null or "unknown" as appropriate.
            - Only use the allowed industry values.
            - For beeLevel, prefer the explicit B-BBEE contributor level or stated BEE preference level from the documents. If not stated, use "unknown".
            - If the documents are an advert rather than a full tender pack, say so in documentType and explain the limitation in completeTenderDescription.

                        ${manifestContext}

            DOCUMENTS:
            ${documentBundle}
        """.trimIndent()
    }

        private fun buildGemmaRequirementsPrompt(
                tenderId: String,
                manifestContext: String,
                documentBundle: String,
        ): String {
                return """
                        You are extracting a complete list of tender requirements for tender ${tenderId}.

                        Return exactly one valid JSON object and nothing else.
                        Schema:
                        {
                            "requirements": [
                                {
                                    "category": "compliance|technical|professional_body|experience|pricing|administrative|mandatory_document|other",
                                    "requirement": "specific requirement text",
                                    "mandatory": true,
                                    "evidence": "short quote or paraphrase from the document"
                                }
                            ]
                        }

                        Rules:
                        - Include business compliance, registrations, tax, CSD, CIDB, ISO, NHBRC, professional bodies, mandatory forms, pricing rules, delivery requirements, and technical requirements when present.
                        - Be exhaustive, but do not invent requirements.
                        - Use an empty array if nothing is present.

                        ${manifestContext}

                        DOCUMENTS:
                        ${documentBundle}
                """.trimIndent()
        }

        private fun buildGemmaBoqPrompt(
                tenderId: String,
                manifestContext: String,
                documentBundle: String,
        ): String {
                return """
                        You are extracting the bill of quantities or schedule of items for tender ${tenderId}.

                        Return exactly one valid JSON object and nothing else.
                        Schema:
                        {
                            "billOfQuantities": [
                                {
                                    "item": "line item name",
                                    "description": "line item description",
                                    "quantity": "quantity text if present",
                                    "unit": "unit if present",
                                    "rate": "rate text if present",
                                    "amount": "amount text if present",
                                    "notes": "extra notes if present"
                                }
                            ]
                        }

                        Rules:
                        - Include every identifiable BOQ or schedule item from the provided text.
                        - If there is no BOQ in the provided text, return an empty array.
                        - Do not invent quantities or prices.

                        ${manifestContext}

                        DOCUMENTS:
                        ${documentBundle}
                """.trimIndent()
        }

    private fun extractJsonObject(rawResponse: String): JSONObject {
        val trimmed = rawResponse.trim()
        val codeFenceStripped =
            trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        return try {
            JSONObject(sanitizeGemmaJsonCandidate(codeFenceStripped))
        } catch (_: Exception) {
            val start = codeFenceStripped.indexOf('{')
            val end = codeFenceStripped.lastIndexOf('}')
            if (start == -1 || end == -1 || end <= start) {
                throw IllegalArgumentException("No JSON object found in Gemma response.")
            }
            JSONObject(sanitizeGemmaJsonCandidate(codeFenceStripped.substring(start, end + 1)))
        }
    }

    private fun sanitizeGemmaJsonCandidate(rawJson: String): String {
        val cleaned = rawJson.trim()
        val output = StringBuilder(cleaned.length)
        var index = 0

        while (index < cleaned.length) {
            val current = cleaned[index]

            if (current == ',') {
                var lookAhead = index + 1
                while (lookAhead < cleaned.length && cleaned[lookAhead].isWhitespace()) {
                    lookAhead++
                }

                if (lookAhead < cleaned.length) {
                    val next = cleaned[lookAhead]
                    if (next == ',' || next == '}' || next == ']') {
                        index++
                        continue
                    }
                }
            }

            if ((current == '{' || current == '[') && index + 1 < cleaned.length) {
                output.append(current)
                var lookAhead = index + 1
                while (lookAhead < cleaned.length && cleaned[lookAhead].isWhitespace()) {
                    output.append(cleaned[lookAhead])
                    lookAhead++
                }
                if (lookAhead < cleaned.length && cleaned[lookAhead] == ',') {
                    index = lookAhead + 1
                    continue
                }
                index++
                continue
            }

            output.append(current)
            index++
        }

        return output.toString()
    }

    private fun mergeGemmaEnrichmentIntoManifest(folder: File, enrichment: JSONObject) {
        val manifestFile = File(folder, "manifest.json")
        val manifest = JSONObject(manifestFile.readText())
        manifest.put("documentType", enrichment.optString("documentType", "unknown"))
        manifest.put("briefDescription", enrichment.optString("briefDescription", ""))
        manifest.put("industry", extractIndustryValue(enrichment))
        manifest.put("beeLevel", extractBeeLevelValue(enrichment))
        manifest.remove("industryCategory")
        manifest.put("completeTenderDescription", enrichment.optString("completeTenderDescription", ""))
        manifest.put(
            "estimatedTenderValue",
            enrichment.optJSONObject("estimatedTenderValue") ?: JSONObject.NULL,
        )
        manifest.put(
            "requirements",
            enrichment.optJSONArray("requirements") ?: JSONArray(),
        )
        manifest.put(
            "billOfQuantities",
            enrichment.optJSONArray("billOfQuantities") ?: JSONArray(),
        )
        val normalizedEnrichment = JSONObject(enrichment.toString())
        normalizedEnrichment.put("industry", extractIndustryValue(enrichment))
        normalizedEnrichment.put("beeLevel", extractBeeLevelValue(enrichment))
        normalizedEnrichment.remove("industryCategory")
        manifest.put("gemmaEnrichment", normalizedEnrichment)
        fileManager.writeManifest(folder, manifest.toString(2))
    }

    private fun extractIndustryValue(json: JSONObject): String {
        return json.optString("industry", json.optString("industryCategory", "unknown"))
    }

    private fun extractBeeLevelValue(json: JSONObject): String {
        return json.optString("beeLevel", json.optString("bbbEELevel", json.optString("bbbeeLevel", "unknown")))
    }

    private fun updateGemmaStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                gemmaReadCheckStatusByTender = _uiState.value.gemmaReadCheckStatusByTender + (tenderId to status)
            )
    }

    private fun updateGemmaResult(tenderId: String, result: String) {
        _uiState.value =
            _uiState.value.copy(
                gemmaReadCheckResultByTender = _uiState.value.gemmaReadCheckResultByTender + (tenderId to result)
            )
    }

    private fun updateGemmaEnrichmentStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                gemmaEnrichmentStatusByTender = _uiState.value.gemmaEnrichmentStatusByTender + (tenderId to status)
            )
    }

    private fun updateFirebaseUploadStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                firebaseUploadStatusByTender = _uiState.value.firebaseUploadStatusByTender + (tenderId to status)
            )
    }

    private fun resetProcessingStatus(isScraping: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                isScraping = isScraping,
                gemmaReadCheckStatusByTender = emptyMap(),
                gemmaReadCheckResultByTender = emptyMap(),
                gemmaEnrichmentStatusByTender = emptyMap(),
                firebaseUploadStatusByTender = emptyMap(),
            )
    }
}