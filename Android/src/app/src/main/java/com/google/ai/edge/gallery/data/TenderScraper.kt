package com.google.ai.edge.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

class TenderScraper(
    private val context: Context,
    private val fileManager: TenderFileManager,
) {

    companion object {
        private const val OCR_FALLBACK_MIN_TEXT_CHARS = 200
        private const val OCR_RENDER_SCALE = 2
        private const val OCR_MAX_PAGES = 6
    }

    fun fetchLatestTenders(limit: Int) {
        scrapeTenderList(limit)
    }

    fun scrapeTenderList(limit: Int = -1) {
        try {
            val pageSize = if (limit > 0) limit else 5
            fileManager.clearTenderFolders()

            // Get the paginated data
            val client = OkHttpClient()
            val url = "https://www.etenders.gov.za/Home/PaginatedTenderOpportunities?draw=1&start=0&length=$pageSize&status=1&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=desc&columns%5B0%5D%5Bdata%5D=AdvertisedDate&columns%5B0%5D%5Bname%5D=&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true&columns%5B0%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B0%5D%5Bsearch%5D%5Bregex%5D=false"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .addHeader("Referer", "https://www.etenders.gov.za/Home/opportunities?id=1")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            val response = client.newCall(request).execute()
            Log.d("ScraperDebug", "API GET response code: ${response.code}")
            val responseBody = response.body?.string()
            if (responseBody == null || responseBody.isEmpty()) {
                Log.e("ScraperError", "Empty response from API")
                return
            }

            Log.d("ScraperDebug", "API response length: ${responseBody.length}")
            Log.d("ScraperDebug", "API response preview: ${responseBody.take(500)}")

            // Parse the response as JSON object (DataTable format)
            val jsonObject = JSONObject(responseBody)
            val dataArray = jsonObject.getJSONArray("data")
            Log.d("ScraperDebug", "Parsed data array with ${dataArray.length()} items")
            if (dataArray.length() > 0) {
                val firstTender = dataArray.getJSONObject(0)
                Log.d("ScraperDebug", "First tender: ${firstTender}")
            }

            var processed = 0
            for (i in 0 until dataArray.length()) {
                if (limit > 0 && processed >= limit) break

                val tenderObj = dataArray.getJSONObject(i)
                val tenderNo = tenderObj.getString("tender_No")
                val description = tenderObj.getString("description")

                Log.d("ScraperDebug", "Processing tender: $tenderNo - $description")

                try {
                    val folder = fileManager.getTenderFolder(tenderNo)
                    fileManager.writeManifest(folder, tenderObj.toString(2))

                    val supportDocuments = tenderObj.optJSONArray("supportDocument") ?: JSONArray()
                    if (supportDocuments.length() > 0) {
                        fileManager.saveTextFile(folder, "support-documents.json", supportDocuments.toString(2))
                        downloadSupportDocuments(client, tenderNo, supportDocuments)
                    }

                    Log.d(
                        "ScraperDebug",
                        "Saved tender $tenderNo with ${supportDocuments.length()} support document entries",
                    )
                    processed++
                } catch (e: Exception) {
                    Log.e("ScraperError", "Failed to process tender $tenderNo", e)
                }
            }
            Log.d("ScraperDebug", "Processed $processed tenders")
        } catch (e: Exception) {
            Log.e("ScraperError", "Error scraping tenders", e)
        }
    }

    private fun downloadSupportDocuments(
        client: OkHttpClient,
        tenderNumber: String,
        supportDocuments: JSONArray,
    ) {
        for (index in 0 until supportDocuments.length()) {
            val supportDocument = supportDocuments.optJSONObject(index) ?: continue
            val supportDocumentId = supportDocument.optString("supportDocumentID")
            val extension = supportDocument.optString("extension", "")
            val fileName = supportDocument.optString(
                "fileName",
                "$supportDocumentId$extension",
            )

            if (supportDocumentId.isBlank()) {
                continue
            }

            val encodedFileName = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
            val blobName = "$supportDocumentId$extension"
            val downloadUrl =
                "https://www.etenders.gov.za/home/Download/?blobName=$blobName&downloadedFileName=$encodedFileName"

            Log.d("ScraperDebug", "Downloading attachment for $tenderNumber: $fileName")
            downloadFile(client, downloadUrl, tenderNumber, fileName)
        }
    }

    private fun downloadFile(client: OkHttpClient, url: String, tenderNumber: String, filename: String) {
        try {
            val request =
                Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    )
                    .addHeader("Referer", "https://www.etenders.gov.za/Home/opportunities?id=1")
                    .build()
            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes()
            if (response.isSuccessful && bytes != null) {
                val folder = fileManager.getTenderFolder(tenderNumber)
                fileManager.saveDocument(folder, filename, bytes)
                Log.d("ScraperDebug", "Downloaded $filename for tender $tenderNumber")
            } else {
                Log.w("ScraperDebug", "Attachment download failed for $url with code ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("ScraperError", "Failed to download $url", e)
        }
    }

    fun extractText(file: File): String {
        val pdfText = extractPdfText(file)
        if (!shouldUseOcrFallback(pdfText)) {
            return pdfText
        }

        Log.d("ScraperDebug", "Using ML Kit OCR fallback for ${file.name}")
        val ocrText = extractPdfTextWithMlKit(file)
        return when {
            ocrText.isBlank() -> pdfText
            pdfText.isBlank() -> ocrText
            ocrText.length > pdfText.length -> ocrText
            else -> listOf(pdfText.trim(), ocrText.trim()).joinToString(separator = "\n\n")
        }
    }

    private fun extractPdfText(file: File): String {
        val document = PDDocument.load(file)
        return try {
            PDFTextStripper().getText(document)
        } finally {
            document.close()
        }
    }

    private fun shouldUseOcrFallback(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return normalized.length < OCR_FALLBACK_MIN_TEXT_CHARS
    }

    private fun extractPdfTextWithMlKit(file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val pages = mutableListOf<String>()
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor)
            val pageCount = min(renderer.pageCount, OCR_MAX_PAGES)

            for (pageIndex in 0 until pageCount) {
                val page = renderer.openPage(pageIndex)
                try {
                    val bitmap =
                        Bitmap.createBitmap(
                            page.width * OCR_RENDER_SCALE,
                            page.height * OCR_RENDER_SCALE,
                            Bitmap.Config.ARGB_8888,
                        )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        val pageText = result.text.trim()
                        if (pageText.isNotBlank()) {
                            pages += "PAGE ${pageIndex + 1}\n$pageText"
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            Log.e("ScraperError", "ML Kit OCR fallback failed for ${file.name}", e)
        } finally {
            renderer?.close()
            fileDescriptor?.close()
            recognizer.close()
        }

        return pages.joinToString(separator = "\n\n")
    }

    fun generateManifest(rawText: String): String {
        val prompt = "Extract the following fields from this tender text into a single JSON object: referenceNumber, procuringEntity, closingDate, and cidbGrade. Return ONLY valid JSON.\n\n$rawText"
        // TODO: Call the InferenceModel (Gemma 4 E4B) with the prompt
        // For now, return a placeholder JSON
        return "{\"referenceNumber\":\"PLACEHOLDER\",\"procuringEntity\":\"PLACEHOLDER\",\"closingDate\":\"PLACEHOLDER\",\"cidbGrade\":\"PLACEHOLDER\"}"
    }

    private fun generateManifest(rawText: String, tenderNumber: String, title: String): String {
        return """
        {
            "tender_number": "$tenderNumber",
            "title": "$title",
            "extracted_text": "$rawText"
        }
        """.trimIndent()
    }

    fun syncTenderToFirebase(tenderFolder: File) {
        try {
            val storage = FirebaseStorage.getInstance()
            val folderName = tenderFolder.name
            val storageRef = storage.reference.child("tenders/$folderName")

            tenderFolder.listFiles()?.forEach { file ->
                val fileRef = storageRef.child(file.name)
                val uploadTask = fileRef.putFile(Uri.fromFile(file))
                // Note: In a real app, handle upload success/failure with listeners
                uploadTask.addOnSuccessListener {
                    // Upload successful
                }.addOnFailureListener { exception ->
                    // Handle failure
                    exception.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}