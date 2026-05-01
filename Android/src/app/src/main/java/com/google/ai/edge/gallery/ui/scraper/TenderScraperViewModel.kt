package com.google.ai.edge.gallery.ui.scraper

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class TenderFolder(
    val tenderId: String,
    val files: List<File>
)

data class ScraperUiState(
    val isScraping: Boolean = false,
    val downloadedTenders: List<TenderFolder> = emptyList()
)

@HiltViewModel
class TenderScraperViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val fileManager = TenderFileManager(context)
    private val scraper = TenderScraper(fileManager)

    private val _uiState = MutableStateFlow(ScraperUiState())
    val uiState: StateFlow<ScraperUiState> = _uiState

    fun startScraping(limit: Int) {
        Log.d("ScraperDebug", "Button clicked, starting scrape for $limit tenders...")
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("ScraperDebug", "Setting isScraping to true")
            _uiState.value = _uiState.value.copy(isScraping = true)
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
}