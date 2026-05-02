package com.google.ai.edge.gallery.data

import android.content.Context
import java.io.File

class TenderFileManager(private val context: Context) {

    companion object {
        private const val SCRAPE_SESSION_FILENAME = "scrape-automation-session.json"
        private const val SCRAPE_SESSION_MARKER_FILENAME = ".scrape-session-id"
        private const val FIREBASE_UPLOADED_MARKER_FILENAME = ".firebase-uploaded"
        private const val GEMMA_ENRICHMENT_FILENAME = "gemma-manifest-enrichment.json"
    }

    private fun sanitizeTenderNumber(tenderNumber: String): String {
        return tenderNumber.replace("/", "_").replace(" ", "_")
    }

    fun normalizeTenderId(tenderNumber: String): String {
        return sanitizeTenderNumber(tenderNumber)
    }

    private fun getTendersDir(): File {
        val tendersDir = File(context.getExternalFilesDir(null), "tenders")
        if (!tendersDir.exists()) {
            tendersDir.mkdirs()
        }
        return tendersDir
    }

    fun hasTenderFolder(tenderNumber: String): Boolean {
        val sanitized = sanitizeTenderNumber(tenderNumber)
        return File(getTendersDir(), sanitized).exists()
    }

    fun clearTenderFolders() {
        getTendersDir().listFiles()?.forEach { folder ->
            folder.deleteRecursively()
        }
    }

    fun getTenderFolder(tenderNumber: String): File {
        val sanitized = sanitizeTenderNumber(tenderNumber)
        val tenderFolder = File(getTendersDir(), sanitized)
        if (!tenderFolder.exists()) {
            tenderFolder.mkdirs()
        }
        return tenderFolder
    }

    fun listTenderFolders(): List<File> {
        return getTendersDir().listFiles { file -> file.isDirectory }?.toList().orEmpty()
    }

    fun clearTenderFolder(tenderNumber: String): File {
        val folder = getTenderFolder(tenderNumber)
        folder.listFiles()?.forEach { it.deleteRecursively() }
        return folder
    }

    fun markTenderUploaded(folder: File) {
        File(folder, FIREBASE_UPLOADED_MARKER_FILENAME).writeText(System.currentTimeMillis().toString())
    }

    fun clearTenderUploadedMarker(folder: File) {
        File(folder, FIREBASE_UPLOADED_MARKER_FILENAME).delete()
    }

    fun isTenderUploaded(folder: File): Boolean {
        return File(folder, FIREBASE_UPLOADED_MARKER_FILENAME).exists()
    }

    fun hasGemmaEnrichment(folder: File): Boolean {
        return File(folder, GEMMA_ENRICHMENT_FILENAME).exists()
    }

    fun listPendingTenderIds(): List<String> {
        return listTenderFolders()
            .filter { folder -> !isTenderUploaded(folder) || !hasGemmaEnrichment(folder) }
            .map { it.name }
            .sorted()
    }

    fun saveScrapeAutomationSession(content: String) {
        File(getTendersDir(), SCRAPE_SESSION_FILENAME).writeText(content)
    }

    fun readScrapeAutomationSession(): String? {
        val file = File(getTendersDir(), SCRAPE_SESSION_FILENAME)
        return if (file.exists()) file.readText() else null
    }

    fun clearScrapeAutomationSession() {
        File(getTendersDir(), SCRAPE_SESSION_FILENAME).delete()
    }

    fun markTenderFolderSession(folder: File, sessionId: String) {
        File(folder, SCRAPE_SESSION_MARKER_FILENAME).writeText(sessionId)
    }

    fun findTenderIdsForSession(sessionId: String): List<String> {
        return listTenderFolders()
            .filter { folder ->
                val markerFile = File(folder, SCRAPE_SESSION_MARKER_FILENAME)
                markerFile.exists() && markerFile.readText().trim() == sessionId
            }
            .map { it.name }
    }

    fun saveDocument(folder: File, filename: String, bytes: ByteArray) {
        val file = File(folder, filename)
        file.writeBytes(bytes)
    }

    fun saveTextFile(folder: File, filename: String, content: String) {
        val file = File(folder, filename)
        file.writeText(content)
    }

    fun writeManifest(folder: File, jsonContent: String) {
        val manifestFile = File(folder, "manifest.json")
        manifestFile.writeText(jsonContent)
    }
}