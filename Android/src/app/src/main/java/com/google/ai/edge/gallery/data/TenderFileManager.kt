package com.google.ai.edge.gallery.data

import android.content.Context
import java.io.File

class TenderFileManager(private val context: Context) {

    fun clearTenderFolders() {
        val tendersDir = File(context.getExternalFilesDir(null), "tenders")
        tendersDir.listFiles()?.forEach { folder ->
            folder.deleteRecursively()
        }
    }

    fun getTenderFolder(tenderNumber: String): File {
        val sanitized = tenderNumber.replace("/", "_").replace(" ", "_")
        val tendersDir = File(context.getExternalFilesDir(null), "tenders")
        val tenderFolder = File(tendersDir, sanitized)
        if (!tenderFolder.exists()) {
            tenderFolder.mkdirs()
        }
        return tenderFolder
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