package com.google.ai.edge.gallery.infrastructure

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.storage.FileDownloadTask
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ListResult
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "AGFirebaseSync"
private const val DEFAULT_BUCKET_URL = "gs://taskme-478416.firebasestorage.app"
private const val DEFAULT_PROJECT_ID = "taskme-478416"
private const val DEFAULT_STORAGE_BUCKET = "taskme-478416.firebasestorage.app"
private const val MANUAL_STORAGE_APP_NAME = "taskme-storage"
private const val MANUAL_STORAGE_APP_ID = "1:478416000000:android:taskme478416"
private const val MANUAL_STORAGE_API_KEY = "AIzaSyTaskmeStoragePlaceholder"
private const val MAX_MANIFEST_BYTES = 512 * 1024L

private val CLOSING_DATE_PATTERNS =
  listOf(
    "yyyy-MM-dd",
    "yyyy/MM/dd",
    "dd/MM/yyyy",
    "d/M/yyyy",
    "dd-MM-yyyy",
    "d-M-yyyy",
    "dd MMM yyyy",
    "d MMM yyyy",
    "dd MMMM yyyy",
    "d MMMM yyyy",
  )

private val CLOSING_DATE_FORMATTERS =
  CLOSING_DATE_PATTERNS.map { pattern -> DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH) }

enum class TenderUploadStage {
  JSON,
  PDF,
}

data class TenderUploadProgress(
  val stage: TenderUploadStage,
  val bytesTransferred: Long,
  val totalByteCount: Long,
  val fractionComplete: Float,
)

data class TenderUploadResult(
  val packageId: String,
  val jsonPath: String,
  val pdfPath: String,
  val jsonDownloadUrl: String,
  val pdfDownloadUrl: String,
)

data class TenderFolderUploadResult(
  val tenderId: String,
  val uploadedPaths: List<String>,
)

data class FirebaseTenderFolder(
  val tenderId: String,
)

data class TenderFolderDownloadResult(
  val tenderId: String,
  val downloadedFiles: List<String>,
)

data class ExpiredTenderCleanupResult(
  val deletedTenderIds: List<String>,
  val retainedTenderIds: List<String>,
  val unreadableTenderIds: List<String>,
)

class FirebaseSync(
  private val context: Context,
) {
  var progressListener: ((TenderUploadProgress) -> Unit)? = null

  private val firebaseApp: FirebaseApp by lazy {
    FirebaseApp.getApps(context).firstOrNull { it.name == MANUAL_STORAGE_APP_NAME }
      ?: runCatching { FirebaseApp.getInstance() }.getOrNull()
      ?: FirebaseApp.initializeApp(
        context,
        FirebaseOptions.Builder()
          .setApplicationId(MANUAL_STORAGE_APP_ID)
          .setApiKey(MANUAL_STORAGE_API_KEY)
          .setProjectId(DEFAULT_PROJECT_ID)
          .setStorageBucket(DEFAULT_STORAGE_BUCKET)
          .build(),
        MANUAL_STORAGE_APP_NAME,
      )
      ?: throw IllegalStateException("Unable to initialize Firebase Storage app.")
  }

  private val storage: FirebaseStorage by lazy {
    FirebaseStorage.getInstance(firebaseApp, DEFAULT_BUCKET_URL)
  }

  suspend fun uploadTenderFolder(folder: File): TenderFolderUploadResult =
    withContext(Dispatchers.IO) {
      require(folder.exists() && folder.isDirectory) { "Tender folder does not exist: ${folder.absolutePath}" }

      val files =
        folder.listFiles()
          ?.filter { it.isFile }
          ?.filterNot { it.name.startsWith(".") }
          ?.sortedBy { it.name }
          .orEmpty()
      require(files.isNotEmpty()) { "Tender folder is empty: ${folder.absolutePath}" }

      val tenderRoot = storage.reference.child("tenders").child(folder.name)
      val uploadedPaths = mutableListOf<String>()

      for (file in files) {
        val fileRef = tenderRoot.child(file.name)
        uploadFile(fileRef, file)
        uploadedPaths += fileRef.path
      }

      TenderFolderUploadResult(
        tenderId = folder.name,
        uploadedPaths = uploadedPaths,
      )
    }

  suspend fun listTenderFolders(): List<FirebaseTenderFolder> =
    withContext(Dispatchers.IO) {
      val root = storage.reference.child("tenders")
      val result = root.listAll().awaitResult()
      result.prefixes
        .map { FirebaseTenderFolder(tenderId = it.name) }
        .sortedBy { it.tenderId }
    }

  suspend fun downloadTenderFolder(tenderId: String, targetFolder: File): TenderFolderDownloadResult =
    withContext(Dispatchers.IO) {
      val root = storage.reference.child("tenders").child(tenderId)
      val result = root.listAll().awaitResult()
      require(result.items.isNotEmpty()) { "No files found in Firebase for tender $tenderId" }

      if (!targetFolder.exists()) {
        targetFolder.mkdirs()
      }
      targetFolder.listFiles()?.forEach { file -> file.deleteRecursively() }

      val downloadedFiles = mutableListOf<String>()
      for (item in result.items) {
        val localFile = File(targetFolder, item.name)
        item.getFile(localFile).awaitDownload()
        downloadedFiles += localFile.name
      }

      TenderFolderDownloadResult(
        tenderId = tenderId,
        downloadedFiles = downloadedFiles,
      )
    }

  suspend fun removeExpiredTenderFolders(today: LocalDate = LocalDate.now()): ExpiredTenderCleanupResult =
    withContext(Dispatchers.IO) {
      val root = storage.reference.child("tenders")
      val result = root.listAll().awaitResult()
      val deletedTenderIds = mutableListOf<String>()
      val retainedTenderIds = mutableListOf<String>()
      val unreadableTenderIds = mutableListOf<String>()

      for (folderRef in result.prefixes.sortedBy { it.name }) {
        val manifestJson = runCatching { readTenderManifest(folderRef) }.getOrNull()
        if (manifestJson == null) {
          unreadableTenderIds += folderRef.name
          continue
        }

        val closingDateRaw =
          manifestJson.optString("closing_Date")
            .ifBlank { manifestJson.optString("closingDate") }
            .trim()
        val closingDate = parseClosingDate(closingDateRaw)

        if (closingDate == null) {
          unreadableTenderIds += folderRef.name
          continue
        }

        if (closingDate.isBefore(today)) {
          deleteFolderRecursively(folderRef)
          deletedTenderIds += folderRef.name
        } else {
          retainedTenderIds += folderRef.name
        }
      }

      ExpiredTenderCleanupResult(
        deletedTenderIds = deletedTenderIds,
        retainedTenderIds = retainedTenderIds,
        unreadableTenderIds = unreadableTenderIds,
      )
    }

  suspend fun uploadTenderPackage(jsonPayload: String, pdfUri: Uri): TenderUploadResult =
    withContext(Dispatchers.IO) {
      require(jsonPayload.isNotBlank()) { "jsonPayload must not be blank" }

      val pdfFileName = resolvePdfFileName(pdfUri)
      val packageId = UUID.randomUUID().toString()
      val packageRoot = storage.reference.child("tender-packages").child(packageId)
      val jsonRef = packageRoot.child("metadata.json")
      val pdfRef = packageRoot.child(pdfFileName)

      uploadJson(jsonRef, jsonPayload)
      uploadPdf(pdfRef, pdfUri)

      val jsonDownloadUrl = jsonRef.downloadUrl.awaitUrl()
      val pdfDownloadUrl = pdfRef.downloadUrl.awaitUrl()

      TenderUploadResult(
        packageId = packageId,
        jsonPath = jsonRef.path,
        pdfPath = pdfRef.path,
        jsonDownloadUrl = jsonDownloadUrl,
        pdfDownloadUrl = pdfDownloadUrl,
      )
    }

  private suspend fun uploadJson(reference: StorageReference, jsonPayload: String) {
    val metadata = StorageMetadata.Builder().setContentType("application/json").build()
    val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
    Log.d(TAG, "Uploading tender metadata JSON to ${reference.path}")
    reference.putBytes(bytes, metadata).awaitWithProgress(TenderUploadStage.JSON)
  }

  private suspend fun uploadPdf(reference: StorageReference, pdfUri: Uri) {
    val metadata = StorageMetadata.Builder().setContentType("application/pdf").build()
    val inputStream =
      context.contentResolver.openInputStream(pdfUri)
        ?: throw IOException("Unable to open PDF uri: $pdfUri")

    Log.d(TAG, "Uploading tender PDF to ${reference.path}")
    inputStream.use { stream ->
      reference.putStream(stream, metadata).awaitWithProgress(TenderUploadStage.PDF)
    }
  }

  private suspend fun uploadFile(reference: StorageReference, file: File) {
    val metadata =
      StorageMetadata.Builder()
        .setContentType(resolveContentType(file))
        .build()
    Log.d(TAG, "Uploading tender file ${file.name} to ${reference.path}")
    FileInputStream(file).use { stream ->
      reference.putStream(stream, metadata).awaitWithProgress(TenderUploadStage.PDF)
    }
  }

  private suspend fun readTenderManifest(folderRef: StorageReference): JSONObject {
    val manifestBytes = folderRef.child("manifest.json").getBytes(MAX_MANIFEST_BYTES).awaitResult()
    return JSONObject(String(manifestBytes, Charsets.UTF_8))
  }

  private suspend fun deleteFolderRecursively(folderRef: StorageReference) {
    val result = folderRef.listAll().awaitResult()
    for (childPrefix in result.prefixes) {
      deleteFolderRecursively(childPrefix)
    }
    for (item in result.items) {
      item.delete().awaitResult()
    }
  }

  private fun parseClosingDate(rawValue: String): LocalDate? {
    if (rawValue.isBlank()) {
      return null
    }

    val normalized = rawValue.replace(Regex("\\s+"), " ").trim()
    val candidates =
      buildList {
        add(normalized)
        DATE_TOKEN_REGEX.find(normalized)?.value?.let(::add)
      }.distinct()

    for (candidate in candidates) {
      for (formatter in CLOSING_DATE_FORMATTERS) {
        try {
          return LocalDate.parse(candidate, formatter)
        } catch (_: DateTimeParseException) {
        }
      }
    }

    return null
  }

  private suspend fun UploadTask.awaitWithProgress(stage: TenderUploadStage) =
    suspendCancellableCoroutine<Unit> { continuation ->
      addOnProgressListener { snapshot ->
        val total = snapshot.totalByteCount.coerceAtLeast(1L)
        val fraction = snapshot.bytesTransferred.toFloat() / total.toFloat()
        progressListener?.invoke(
          TenderUploadProgress(
            stage = stage,
            bytesTransferred = snapshot.bytesTransferred,
            totalByteCount = snapshot.totalByteCount,
            fractionComplete = fraction,
          )
        )
      }

      addOnSuccessListener {
        progressListener?.invoke(
          TenderUploadProgress(
            stage = stage,
            bytesTransferred = it.totalByteCount,
            totalByteCount = it.totalByteCount,
            fractionComplete = 1f,
          )
        )
        if (continuation.isActive) {
          continuation.resume(Unit)
        }
      }

      addOnFailureListener { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(error)
        }
      }

      continuation.invokeOnCancellation { cancel() }
    }

  private suspend fun com.google.android.gms.tasks.Task<Uri>.awaitUrl(): String =
    suspendCancellableCoroutine { continuation ->
      addOnSuccessListener { uri ->
        if (continuation.isActive) {
          continuation.resume(uri.toString())
        }
      }
      addOnFailureListener { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(error)
        }
      }
    }

  private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
      addOnSuccessListener { result ->
        if (continuation.isActive) {
          continuation.resume(result)
        }
      }
      addOnFailureListener { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(error)
        }
      }
    }

  private suspend fun FileDownloadTask.awaitDownload() =
    suspendCancellableCoroutine<Unit> { continuation ->
      addOnSuccessListener {
        if (continuation.isActive) {
          continuation.resume(Unit)
        }
      }
      addOnFailureListener { error ->
        if (continuation.isActive) {
          continuation.resumeWithException(error)
        }
      }
      continuation.invokeOnCancellation { cancel() }
    }

  private fun resolvePdfFileName(pdfUri: Uri): String {
    val nameFromCursor =
      context.contentResolver.query(pdfUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            cursor.getString(0)
          } else {
            null
          }
        }

    val fallbackName = pdfUri.lastPathSegment?.substringAfterLast('/') ?: "tender-document.pdf"
    val fileName = (nameFromCursor ?: fallbackName).replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
  }

  private fun resolveContentType(file: File): String {
    return when (file.extension.lowercase()) {
      "json" -> "application/json"
      "pdf" -> "application/pdf"
      "txt", "md", "csv" -> "text/plain"
      else -> "application/octet-stream"
    }
  }

  private companion object {
    val DATE_TOKEN_REGEX =
      Regex(
        """\b(\d{4}[-/]\d{2}[-/]\d{2}|\d{1,2}[/-]\d{1,2}[/-]\d{4}|\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})\b""",
      )
  }
}