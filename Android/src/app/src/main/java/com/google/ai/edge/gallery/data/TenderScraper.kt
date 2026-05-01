package com.google.ai.edge.gallery.data

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
import com.google.firebase.storage.FirebaseStorage
import android.util.Log
import java.io.IOException
import android.net.Uri
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class TenderScraper(private val fileManager: TenderFileManager) {

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
        val document = PDDocument.load(file)
        val stripper = PDFTextStripper()
        val text = stripper.getText(document)
        document.close()
        return text
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