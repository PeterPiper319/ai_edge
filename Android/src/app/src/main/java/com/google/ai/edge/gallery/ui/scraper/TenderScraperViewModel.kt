package com.google.ai.edge.gallery.ui.scraper

import android.content.Context
import android.util.Log
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import com.google.firebase.storage.StorageException
import com.google.ai.edge.gallery.infrastructure.FirebaseSync
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_PROGRESS_COMPLETED
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_PROGRESS_STATUS
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_PROGRESS_TOTAL
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_WORK_NAME
import com.google.ai.edge.gallery.worker.TenderScraperWorker
import com.google.ai.edge.gallery.worker.getScraperConstraints
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
    val scrapeStatus: String = "",
    val hasResumableSession: Boolean = false,
    val isBackgroundScraperRunning: Boolean = false,
    val canResumeBackgroundScraper: Boolean = false,
    val backgroundScraperStatus: String = "",
    val firebaseTenderIds: List<String> = emptyList(),
    val firebaseListStatus: String = "",
    val isCleaningExpiredFirebaseTenders: Boolean = false,
    val firebaseCleanupStatus: String = "",
    val firebaseDownloadStatusByTender: Map<String, String> = emptyMap(),
    val downloadedTenders: List<TenderFolder> = emptyList(),
    val gemmaReadCheckStatusByTender: Map<String, String> = emptyMap(),
    val gemmaReadCheckResultByTender: Map<String, String> = emptyMap(),
    val gemmaEnrichmentStatusByTender: Map<String, String> = emptyMap(),
    val firebaseUploadStatusByTender: Map<String, String> = emptyMap()
)

private data class ScrapeAutomationSession(
    val sessionId: String,
    val targetLimit: Int,
    val scrapedTenderIds: MutableList<String> = mutableListOf(),
    val enrichedTenderIds: MutableList<String> = mutableListOf(),
    val uploadedTenderIds: MutableList<String> = mutableListOf(),
    var stage: String = "scraping",
    var currentTenderId: String? = null,
    var status: String = "",
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("targetLimit", targetLimit)
            put("scrapedTenderIds", JSONArray(scrapedTenderIds))
            put("enrichedTenderIds", JSONArray(enrichedTenderIds))
            put("uploadedTenderIds", JSONArray(uploadedTenderIds))
            put("stage", stage)
            put("currentTenderId", currentTenderId ?: JSONObject.NULL)
            put("status", status)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ScrapeAutomationSession {
            return ScrapeAutomationSession(
                sessionId = json.getString("sessionId"),
                targetLimit = json.optInt("targetLimit", 100),
                scrapedTenderIds = jsonArrayToMutableList(json.optJSONArray("scrapedTenderIds")),
                enrichedTenderIds = jsonArrayToMutableList(json.optJSONArray("enrichedTenderIds")),
                uploadedTenderIds = jsonArrayToMutableList(json.optJSONArray("uploadedTenderIds")),
                stage = json.optString("stage", "scraping"),
                currentTenderId = json.optString("currentTenderId", "").ifBlank { null },
                status = json.optString("status", ""),
            )
        }

        private fun jsonArrayToMutableList(array: JSONArray?): MutableList<String> {
            return mutableListOf<String>().apply {
                if (array == null) return@apply
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        }
    }
}

@HiltViewModel
class TenderScraperViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "AGTenderScraperVM"
        private const val GEMMA_READ_CHECK_FILENAME = "gemma-read-check.txt"
        private const val GEMMA_ENRICHMENT_FILENAME = "gemma-manifest-enrichment.json"
        private const val SESSION_STAGE_SCRAPING = "scraping"
        private const val SESSION_STAGE_ENRICHING = "enriching"
        private const val SESSION_STAGE_UPLOADING = "uploading"
        private const val SESSION_STAGE_STOPPED = "stopped"
        private const val SESSION_STAGE_FAILED = "failed"
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
    private val workManager = WorkManager.getInstance(context)
    private var stopRequested = false
    private var activeAutomationSession: ScrapeAutomationSession? = null
    private var backgroundWorkerObserver: Observer<List<WorkInfo>>? = null

    private val _uiState = MutableStateFlow(ScraperUiState())
    val uiState: StateFlow<ScraperUiState> = _uiState

    init {
        loadDownloadedTenders()
        restoreAutomationSession()
        loadFirebaseTenders()
        observeBackgroundScraperWork()
    }

    fun enqueueBackgroundScraper() {
        _uiState.value =
            _uiState.value.copy(
                backgroundScraperStatus = "Background scraper scheduled. Waiting for Wi-Fi and charging.",
                canResumeBackgroundScraper = false,
            )
        enqueueBackgroundScraper(existingWorkPolicy = ExistingWorkPolicy.KEEP)
    }

    fun cancelBackgroundScraper() {
        workManager.cancelUniqueWork(TENDER_SCRAPER_WORK_NAME)
        _uiState.value =
            _uiState.value.copy(
                backgroundScraperStatus = "Cancelling background scraper...",
                canResumeBackgroundScraper = true,
            )
    }

    fun resumeBackgroundScraper() {
        _uiState.value =
            _uiState.value.copy(
                backgroundScraperStatus = "Resuming background scraper from pending tenders...",
                canResumeBackgroundScraper = false,
            )
        enqueueBackgroundScraper(existingWorkPolicy = ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueBackgroundScraper(existingWorkPolicy: ExistingWorkPolicy) {
        val request =
            OneTimeWorkRequestBuilder<TenderScraperWorker>()
                .setConstraints(getScraperConstraints())
                .build()

        workManager.enqueueUniqueWork(TENDER_SCRAPER_WORK_NAME, existingWorkPolicy, request)
    }

    fun startScraping(limit: Int) {
        Log.d("ScraperDebug", "Button clicked, starting scrape for $limit tenders...")
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("ScraperDebug", "Setting isScraping to true")
            resetProcessingStatus(isScraping = true)
            try {
                updateScrapeStatus("Starting scrape for $limit tenders...")
                scraper.fetchLatestTenders(limit, ::updateScrapeStatus)
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
            val session = ScrapeAutomationSession(
                sessionId = System.currentTimeMillis().toString(),
                targetLimit = limit,
                stage = SESSION_STAGE_SCRAPING,
                status = "Starting automated scrape for $limit tenders...",
            )
            runAutomationSession(model, session, isResume = false)
        }
    }

    fun resumeScrapeEnrichAndUpload(model: Model) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = loadAutomationSession() ?: return@launch
            reconcileSessionWithStoredTenders(session)
            runAutomationSession(model, session, isResume = true)
        }
    }

    fun requestStopScraper() {
        stopRequested = true
        activeAutomationSession?.let { session ->
            session.stage = SESSION_STAGE_STOPPED
            session.status = "Stopping after the current step finishes..."
            persistAutomationSession(session)
            updateScrapeStatus(session.status)
            _uiState.value = _uiState.value.copy(hasResumableSession = true)
        }
    }

    private suspend fun runAutomationSession(
        model: Model,
        session: ScrapeAutomationSession,
        isResume: Boolean,
    ) {
        stopRequested = false
        activeAutomationSession = session
        resetProcessingStatus(isScraping = true)
        _uiState.value = _uiState.value.copy(hasResumableSession = false)

        try {
            if (isResume) {
                session.status = "Resuming automation from ${session.stage}..."
            }
            persistAutomationSession(session)
            updateScrapeStatus(session.status)

            val scrapeStillNeeded = session.scrapedTenderIds.size < session.targetLimit
            if (scrapeStillNeeded) {
                val remaining = session.targetLimit - session.scrapedTenderIds.size
                session.stage = SESSION_STAGE_SCRAPING
                session.status = "Scraping remaining $remaining tender(s)..."
                persistAutomationSession(session)
                updateScrapeStatus(session.status)

                val scrapeResult =
                    scraper.fetchLatestTenders(
                        limit = remaining,
                        onStatus = { status ->
                            session.status = status
                            persistAutomationSession(session)
                            updateScrapeStatus(status)
                        },
                        shouldStop = { stopRequested },
                        onNewTenderSaved = { tenderId ->
                            if (!session.scrapedTenderIds.contains(tenderId)) {
                                session.scrapedTenderIds.add(tenderId)
                            }
                            persistAutomationSession(session)
                        },
                        sessionId = session.sessionId,
                    )

                reconcileSessionWithStoredTenders(session)
                loadDownloadedTenders()

                if (scrapeResult.stopped || stopRequested) {
                    stopAutomationSession(session, "Scrape stopped. Resume will continue from the last saved tender.")
                    return
                }

                if (scrapeResult.failureMessage != null) {
                    failAutomationSession(session, "Scrape paused after an error: ${scrapeResult.failureMessage}. Resume will continue from the last saved tender.")
                    return
                }
            }

            if (session.scrapedTenderIds.isEmpty()) {
                stopAutomationSession(session, "No new tenders were saved in this automation session.")
                return
            }

            val total = session.scrapedTenderIds.size
            for ((index, tenderId) in session.scrapedTenderIds.withIndex()) {
                if (stopRequested) {
                    stopAutomationSession(session, "Automation stopped. Resume will continue from $tenderId.")
                    return
                }

                val position = index + 1
                session.currentTenderId = tenderId

                if (!session.enrichedTenderIds.contains(tenderId)) {
                    session.stage = SESSION_STAGE_ENRICHING
                    session.status = "Automation $position/$total: enriching $tenderId"
                    persistAutomationSession(session)
                    updateScrapeStatus(session.status)
                    updateGemmaEnrichmentStatus(
                        tenderId,
                        "Automation $position/$total: starting Gemma enrichment...",
                    )
                    val enriched = enrichManifestWithGemmaInternal(model, tenderId)
                    if (!enriched) {
                        if (stopRequested) {
                            stopAutomationSession(session, "Automation stopped during enrichment for $tenderId.")
                        } else {
                            failAutomationSession(session, "Automation paused on enrichment failure for $tenderId. Resume will retry that tender.")
                        }
                        return
                    }
                    session.enrichedTenderIds.add(tenderId)
                    persistAutomationSession(session)
                }

                if (stopRequested) {
                    stopAutomationSession(session, "Automation stopped before upload for $tenderId.")
                    return
                }

                if (!session.uploadedTenderIds.contains(tenderId)) {
                    session.stage = SESSION_STAGE_UPLOADING
                    session.status = "Automation $position/$total: uploading $tenderId"
                    persistAutomationSession(session)
                    updateScrapeStatus(session.status)
                    updateFirebaseUploadStatus(
                        tenderId,
                        "Automation $position/$total: starting Firebase upload...",
                    )
                    val uploaded = uploadTenderToFirebaseInternal(tenderId)
                    if (!uploaded) {
                        failAutomationSession(session, "Automation paused on upload failure for $tenderId. Resume will retry that tender.")
                        return
                    }
                    session.uploadedTenderIds.add(tenderId)
                    persistAutomationSession(session)
                }
            }

            session.currentTenderId = null
            session.status = "Automation completed for ${session.uploadedTenderIds.size} tender(s)."
            updateScrapeStatus(session.status)
            clearAutomationSession()
        } finally {
            delay(2000)
            activeAutomationSession = null
            _uiState.value = _uiState.value.copy(isScraping = false)
            loadDownloadedTenders()
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

    fun loadFirebaseTenders() {
        viewModelScope.launch(Dispatchers.IO) {
            updateFirebaseListStatus("Loading tender folders from Firebase...")
            try {
                val tenders = firebaseSync.listTenderFolders().map { it.tenderId }
                _uiState.value = _uiState.value.copy(firebaseTenderIds = tenders)
                updateFirebaseListStatus("Loaded ${tenders.size} tender folder(s) from Firebase.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list Firebase tenders", e)
                updateFirebaseListStatus("Failed to load Firebase tenders: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun removeExpiredFirebaseTenders() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value =
                _uiState.value.copy(
                    isCleaningExpiredFirebaseTenders = true,
                    firebaseCleanupStatus = "Checking Firebase tenders for expired closing dates...",
                )

            try {
                val result = firebaseSync.removeExpiredTenderFolders()
                val summary = buildFirebaseCleanupSummary(result)
                _uiState.value =
                    _uiState.value.copy(
                        isCleaningExpiredFirebaseTenders = false,
                        firebaseCleanupStatus = summary,
                    )
                loadFirebaseTenders()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove expired Firebase tenders", e)
                _uiState.value =
                    _uiState.value.copy(
                        isCleaningExpiredFirebaseTenders = false,
                        firebaseCleanupStatus =
                            "Failed to remove expired Firebase tenders: ${e.message ?: "unknown error"}",
                    )
            }
        }
    }

    fun downloadTenderFromFirebase(tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadTenderFromFirebaseInternal(tenderId)
        }
    }

    fun enrichFirebaseTender(model: Model, tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = downloadTenderFromFirebaseInternal(tenderId)
            if (!downloaded) {
                return@launch
            }

            val enriched = enrichManifestWithGemmaInternal(model, tenderId)
            if (!enriched) {
                return@launch
            }

            uploadTenderToFirebaseInternal(tenderId)
        }
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
            fileManager.clearTenderUploadedMarker(folder)
            val result = firebaseSync.uploadTenderFolder(folder)
            fileManager.markTenderUploaded(folder)
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

    private suspend fun downloadTenderFromFirebaseInternal(tenderId: String): Boolean {
        updateFirebaseDownloadStatus(tenderId, "Downloading tender files from Firebase...")
        return try {
            val folder = fileManager.clearTenderFolder(tenderId)
            val result = firebaseSync.downloadTenderFolder(tenderId, folder)
            updateFirebaseDownloadStatus(
                tenderId,
                "Downloaded ${result.downloadedFiles.size} file(s) from Firebase.",
            )
            loadDownloadedTenders()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download tender $tenderId from Firebase", e)
            updateFirebaseDownloadStatus(
                tenderId,
                "Failed to download from Firebase: ${e.message ?: "unknown error"}",
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
        var inString = false
        var escaped = false

        while (index < cleaned.length) {
            val current = cleaned[index]

            if (inString) {
                when {
                    escaped -> {
                        output.append(current)
                        escaped = false
                    }
                    current == '\\' -> {
                        output.append(current)
                        escaped = true
                    }
                    current == '"' -> {
                        val next = nextNonWhitespace(cleaned, index + 1)
                        if (next == ':' || next == ',' || next == '}' || next == ']') {
                            output.append(current)
                            inString = false
                        } else {
                            output.append("\\\"")
                        }
                    }
                    current == '\n' -> output.append("\\n")
                    current == '\r' -> output.append("\\r")
                    current == '\t' -> output.append("\\t")
                    else -> output.append(current)
                }
                index++
                continue
            }

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

            if (current == '"') {
                inString = true
            }

            output.append(current)
            index++
        }

        if (inString) {
            output.append('"')
        }

        return output.toString()
    }

    private fun nextNonWhitespace(value: String, startIndex: Int): Char? {
        var index = startIndex
        while (index < value.length) {
            val current = value[index]
            if (!current.isWhitespace()) {
                return current
            }
            index++
        }
        return null
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

    private fun updateScrapeStatus(status: String) {
        _uiState.value = _uiState.value.copy(scrapeStatus = status)
    }

    private fun updateFirebaseListStatus(status: String) {
        _uiState.value = _uiState.value.copy(firebaseListStatus = status)
    }

    private fun updateFirebaseDownloadStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                firebaseDownloadStatusByTender = _uiState.value.firebaseDownloadStatusByTender + (tenderId to status)
            )
    }

    private fun buildFirebaseCleanupSummary(
        result: com.google.ai.edge.gallery.infrastructure.ExpiredTenderCleanupResult,
    ): String {
        val deletedCount = result.deletedTenderIds.size
        val retainedCount = result.retainedTenderIds.size
        val unreadableCount = result.unreadableTenderIds.size

        val parts = mutableListOf("Removed $deletedCount expired tender(s)", "kept $retainedCount active")
        if (unreadableCount > 0) {
            parts += "skipped $unreadableCount with missing or unreadable closing dates"
        }

        return parts.joinToString(separator = "; ", postfix = ".")
    }

    private fun resetProcessingStatus(isScraping: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                isScraping = isScraping,
                scrapeStatus = "",
                hasResumableSession = false,
                firebaseDownloadStatusByTender = emptyMap(),
                gemmaReadCheckStatusByTender = emptyMap(),
                gemmaReadCheckResultByTender = emptyMap(),
                gemmaEnrichmentStatusByTender = emptyMap(),
                firebaseUploadStatusByTender = emptyMap(),
            )
    }

    private fun restoreAutomationSession() {
        val session = loadAutomationSession() ?: return
        reconcileSessionWithStoredTenders(session)
        activeAutomationSession = session
        _uiState.value =
            _uiState.value.copy(
                scrapeStatus = session.status.ifBlank { "A previous scraper session can be resumed." },
                hasResumableSession = true,
            )
    }

    private fun observeBackgroundScraperWork() {
        val observer = Observer<List<WorkInfo>> { workInfos ->
            val workInfo = workInfos.firstOrNull()
            if (workInfo == null) {
                _uiState.value =
                    _uiState.value.copy(
                        isBackgroundScraperRunning = false,
                        canResumeBackgroundScraper = false,
                    )
                return@Observer
            }

            val progress = workInfo.progress
            val status = progress.getString(TENDER_SCRAPER_PROGRESS_STATUS).orEmpty()
            val completed = progress.getInt(TENDER_SCRAPER_PROGRESS_COMPLETED, 0)
            val total = progress.getInt(TENDER_SCRAPER_PROGRESS_TOTAL, 0)
            val summary =
                when {
                    status.isBlank() -> backgroundStatusForState(workInfo.state)
                    total > 0 -> "$status ($completed/$total)"
                    else -> status
                }

            _uiState.value =
                _uiState.value.copy(
                    isBackgroundScraperRunning =
                        workInfo.state == WorkInfo.State.RUNNING ||
                            workInfo.state == WorkInfo.State.ENQUEUED ||
                            workInfo.state == WorkInfo.State.BLOCKED,
                    canResumeBackgroundScraper =
                        workInfo.state == WorkInfo.State.CANCELLED ||
                            workInfo.state == WorkInfo.State.FAILED,
                    backgroundScraperStatus = summary,
                )
        }

        workManager.getWorkInfosForUniqueWorkLiveData(TENDER_SCRAPER_WORK_NAME).observeForever(observer)
        backgroundWorkerObserver = observer
    }

    private fun backgroundStatusForState(state: WorkInfo.State): String {
        return when (state) {
            WorkInfo.State.ENQUEUED -> "Background scraper queued. Waiting for Wi-Fi and charging."
            WorkInfo.State.RUNNING -> "Background scraper is running..."
            WorkInfo.State.SUCCEEDED -> "Background scraper completed successfully."
            WorkInfo.State.CANCELLED -> "Background scraper cancelled. Resume will continue pending tenders."
            WorkInfo.State.FAILED -> "Background scraper failed. Resume will retry pending tenders."
            WorkInfo.State.BLOCKED -> "Background scraper is blocked and waiting on constraints."
        }
    }

    private fun reconcileSessionWithStoredTenders(session: ScrapeAutomationSession) {
        val markedTenderIds = fileManager.findTenderIdsForSession(session.sessionId)
        markedTenderIds.forEach { tenderId ->
            if (!session.scrapedTenderIds.contains(tenderId)) {
                session.scrapedTenderIds.add(tenderId)
            }
        }
        persistAutomationSession(session)
    }

    private fun loadAutomationSession(): ScrapeAutomationSession? {
        val raw = fileManager.readScrapeAutomationSession() ?: return null
        return try {
            ScrapeAutomationSession.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read scraper automation session", e)
            null
        }
    }

    private fun persistAutomationSession(session: ScrapeAutomationSession) {
        fileManager.saveScrapeAutomationSession(session.toJson().toString(2))
    }

    private fun clearAutomationSession() {
        fileManager.clearScrapeAutomationSession()
        _uiState.value = _uiState.value.copy(hasResumableSession = false)
    }

    private fun stopAutomationSession(session: ScrapeAutomationSession, status: String) {
        session.stage = SESSION_STAGE_STOPPED
        session.currentTenderId = session.currentTenderId
        session.status = status
        persistAutomationSession(session)
        updateScrapeStatus(status)
        _uiState.value = _uiState.value.copy(hasResumableSession = true)
    }

    private fun failAutomationSession(session: ScrapeAutomationSession, status: String) {
        session.stage = SESSION_STAGE_FAILED
        session.status = status
        persistAutomationSession(session)
        updateScrapeStatus(status)
        _uiState.value = _uiState.value.copy(hasResumableSession = true)
    }

    override fun onCleared() {
        backgroundWorkerObserver?.let { observer ->
            workManager.getWorkInfosForUniqueWorkLiveData(TENDER_SCRAPER_WORK_NAME).removeObserver(observer)
        }
        super.onCleared()
    }
}