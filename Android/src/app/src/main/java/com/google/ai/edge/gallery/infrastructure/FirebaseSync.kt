package com.google.ai.edge.gallery.infrastructure

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "AGFirebaseSync"
private const val DEFAULT_BUCKET_URL = "gs://taskme-478416.firebasestorage.app"
private const val DEFAULT_PROJECT_ID = "taskme-478416"
private const val DEFAULT_STORAGE_BUCKET = "taskme-478416.firebasestorage.app"
private const val MANUAL_STORAGE_APP_NAME = "taskme-storage"
private const val MANUAL_STORAGE_APP_ID = "1:478416000000:android:taskme478416"
private const val MANUAL_STORAGE_API_KEY = "AIzaSyTaskmeStoragePlaceholder"

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

      val files = folder.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
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
}