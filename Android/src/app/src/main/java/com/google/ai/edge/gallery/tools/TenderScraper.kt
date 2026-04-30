package com.google.ai.edge.gallery.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private const val TAG = "AGTenderScraper"
private const val DEFAULT_BASE_URL = "https://www.etenders.gov.za"
private const val OPPORTUNITIES_PATH = "/Home/opportunities?id=1"

data class TenderDocumentMetadata(
  val title: String,
  val category: String? = null,
  val advertisedDate: String? = null,
  val closingDate: String? = null,
  val detailUrl: String? = null,
  val documentUrl: String? = null,
  val fileName: String? = null,
  val localPath: String? = null,
)

class TenderScraper(
  context: Context,
  private val client: OkHttpClient = OkHttpClient(),
  private val baseUrl: String = DEFAULT_BASE_URL,
) {
  private val storageDir = File(context.filesDir, "tenders")

  suspend fun getLatestTenders(
    maxPages: Int = 1,
    downloadDocuments: Boolean = true,
  ): List<TenderDocumentMetadata> =
    withContext(Dispatchers.IO) {
      storageDir.mkdirs()

      val firstPageUrl = absoluteUrl(OPPORTUNITIES_PATH)
      val firstPage = fetchDocument(firstPageUrl)
      val listingUrls = discoverListingUrls(firstPageUrl, firstPage, maxPages)
      val tenders = mutableListOf<TenderDocumentMetadata>()

      for (listingUrl in listingUrls) {
        tenders += parseListingPage(fetchDocument(listingUrl), listingUrl, downloadDocuments)
      }

      tenders
        .distinctBy { listOf(it.documentUrl, it.detailUrl, it.title).joinToString("|") }
        .filter { it.title.isNotBlank() }
    }

  suspend fun downloadTenderDocument(metadata: TenderDocumentMetadata): TenderDocumentMetadata =
    withContext(Dispatchers.IO) {
      val documentUrl = metadata.documentUrl ?: return@withContext metadata
      val downloadedFile = downloadPdf(documentUrl, metadata.title)
      metadata.copy(fileName = downloadedFile.name, localPath = downloadedFile.absolutePath)
    }

  private fun parseListingPage(
    document: Document,
    pageUrl: String,
    downloadDocuments: Boolean,
  ): List<TenderDocumentMetadata> {
    val rows =
      document.select("table tbody tr").ifEmpty {
        document.select("tr[role=row], .dataTables_wrapper tbody tr, .table tbody tr")
      }

    val results = mutableListOf<TenderDocumentMetadata>()
    for (row in rows) {
      val metadata = extractMetadata(row, pageUrl) ?: continue
      val enriched = enrichMetadata(metadata, row, downloadDocuments)
      if (enriched != null) {
        results += enriched
      }
    }
    return results
  }

  private fun extractMetadata(row: Element, pageUrl: String): TenderDocumentMetadata? {
    val cells = row.select("td")
    val text = row.text().normalizeWhitespace()
    if (text.isBlank()) {
      return null
    }

    val link = row.selectFirst("a[href]")
    val title =
      cells.getOrNull(1)?.text()?.normalizeWhitespace()?.takeIf { it.isNotBlank() }
        ?: link?.text()?.normalizeWhitespace()?.takeIf { it.isNotBlank() }
        ?: text.takeIf { it.isNotBlank() }
        ?: return null

    val detailUrl =
      link
        ?.attr("abs:href")
        ?.takeIf { it.isNotBlank() && !it.endsWith("#") }
        ?: extractCandidateDetailUrl(row, pageUrl)

    val category = cells.firstOrNull()?.text()?.normalizeWhitespace()?.takeIf { it.isNotBlank() }
    val advertisedDate = cells.getOrNull(3)?.text()?.normalizeWhitespace()?.takeIf { it.isNotBlank() }
    val closingDate = cells.getOrNull(4)?.text()?.normalizeWhitespace()?.takeIf { it.isNotBlank() }

    return TenderDocumentMetadata(
      title = title,
      category = category,
      advertisedDate = advertisedDate,
      closingDate = closingDate,
      detailUrl = detailUrl,
    )
  }

  private fun enrichMetadata(
    metadata: TenderDocumentMetadata,
    row: Element,
    downloadDocuments: Boolean,
  ): TenderDocumentMetadata? {
    val documentUrl = findDocumentUrl(row, metadata.detailUrl)
    val withUrl = metadata.copy(documentUrl = documentUrl)
    if (!downloadDocuments || documentUrl == null) {
      return withUrl
    }

    return runCatching {
        val downloadedFile = downloadPdf(documentUrl, metadata.title)
        withUrl.copy(fileName = downloadedFile.name, localPath = downloadedFile.absolutePath)
      }
      .onFailure { error -> Log.w(TAG, "Failed to download tender PDF: ${metadata.title}", error) }
      .getOrDefault(withUrl)
  }

  private fun findDocumentUrl(row: Element, detailUrl: String?): String? {
    extractPdfUrl(row)?.let { return it }
    if (detailUrl == null) {
      return null
    }

    return runCatching { fetchDocument(detailUrl) }
      .mapCatching { detailDocument ->
        extractPdfUrl(detailDocument)
          ?: detailDocument
            .selectFirst("a[href*=/Document], a[href*=download], a[href*=Download], a[href$=.pdf]")
            ?.attr("abs:href")
            ?.takeIf { it.isNotBlank() }
      }
      .onFailure { error -> Log.w(TAG, "Failed to inspect tender details: $detailUrl", error) }
      .getOrNull()
  }

  private fun extractPdfUrl(element: Element): String? {
    val selectors =
      listOf(
        "a[href$=.pdf]",
        "a[href*=.pdf?]",
        "a[href*=Document]",
        "a[href*=document]",
        "a[href*=Download]",
        "a[href*=download]",
      )

    return selectors
      .asSequence()
      .mapNotNull { selector ->
        element.select(selector).firstNotNullOfOrNull { anchor ->
          anchor.attr("abs:href").takeIf { href ->
            href.isNotBlank() &&
              (href.contains(".pdf", ignoreCase = true) || href.contains("document", ignoreCase = true))
          }
        }
      }
      .firstOrNull()
  }

  private fun discoverListingUrls(firstPageUrl: String, document: Document, maxPages: Int): List<String> {
    if (maxPages <= 1) {
      return listOf(firstPageUrl)
    }

    val urls = linkedSetOf(firstPageUrl)
    document
      .select(".pagination a[href], .paginate_button a[href], a[href*=opportunities]")
      .mapNotNull { anchor ->
        anchor.attr("abs:href").takeIf { href -> href.isNotBlank() && !href.endsWith("#") }
      }
      .forEach { href -> urls += href }

    return urls.take(maxPages)
  }

  private fun fetchDocument(url: String): Document {
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Failed to fetch HTML from $url: ${response.code} ${response.message}")
      }
      val body = response.body.string()
      return Jsoup.parse(body, url)
    }
  }

  private fun downloadPdf(documentUrl: String, title: String): File {
    val request = Request.Builder().url(documentUrl).get().build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException(
          "Failed to download PDF from $documentUrl: ${response.code} ${response.message}"
        )
      }

      val fileName = resolveFileName(response, documentUrl, title)
      val destination = File(storageDir, fileName)
      response.body.byteStream().use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
      }
      return destination
    }
  }

  private fun resolveFileName(
    response: okhttp3.Response,
    documentUrl: String,
    title: String,
  ): String {
    val contentDisposition = response.header("Content-Disposition").orEmpty()
    val headerName =
      contentDisposition
        .substringAfter("filename*=", missingDelimiterValue = "")
        .substringAfter("''", missingDelimiterValue = "")
        .substringBefore(';')
        .ifBlank {
          contentDisposition
            .substringAfter("filename=", missingDelimiterValue = "")
            .trim('"')
            .substringBefore(';')
        }
        .takeIf { it.isNotBlank() }
        ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }

    val urlName = documentUrl.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
    val fallbackName = sanitizeFileName(title) + ".pdf"
    val candidate = headerName ?: urlName ?: fallbackName
    return ensurePdfExtension(sanitizeFileName(candidate))
  }

  private fun extractCandidateDetailUrl(row: Element, pageUrl: String): String? {
    val absolutePageUrl = absoluteUrl(pageUrl)
    return row
      .select("a[href]")
      .mapNotNull { anchor -> anchor.attr("abs:href").takeIf { it.isNotBlank() } }
      .firstOrNull { href ->
        href.startsWith(baseUrl) && href != absolutePageUrl && !href.contains(".pdf", ignoreCase = true)
      }
  }

  private fun absoluteUrl(pathOrUrl: String): String {
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
      return pathOrUrl
    }
    return baseUrl.trimEnd('/') + "/" + pathOrUrl.trimStart('/')
  }

  private fun sanitizeFileName(value: String): String {
    val sanitized = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
    return sanitized.ifBlank { "tender_document" }
  }

  private fun ensurePdfExtension(fileName: String): String {
    return if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"
  }
}

private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()