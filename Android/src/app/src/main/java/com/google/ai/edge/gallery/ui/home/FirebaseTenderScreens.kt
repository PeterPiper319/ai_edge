package com.google.ai.edge.gallery.ui.home

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.scraper.TenderScraperViewModel
import java.io.File

enum class FirebaseTenderPage {
  BROWSER,
  ENRICHMENT,
}

@Composable
fun FirebaseTenderBrowserScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateToScraper: () -> Unit,
  navigateToFirebaseEnrichment: () -> Unit,
) {
  val tenderScraperViewModel: TenderScraperViewModel = hiltViewModel()
  val scraperUiState by tenderScraperViewModel.uiState.collectAsState()
  var searchQuery by remember { mutableStateOf("") }
  var showBottomSheet by remember { mutableStateOf(false) }
  var bottomSheetContent by remember { mutableStateOf("") }
  var tenderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
  var isViewingFiles by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    tenderScraperViewModel.loadFirebaseTenders()
  }

  val filteredTenderIds =
    remember(scraperUiState.firebaseTenderIds, searchQuery) {
      scraperUiState.firebaseTenderIds.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

  FirebaseTenderScreenScaffold(
    title = "Firebase Tenders",
    currentPage = FirebaseTenderPage.BROWSER,
    navigateToScraper = navigateToScraper,
    navigateToFirebaseBrowser = {},
    navigateToFirebaseEnrichment = navigateToFirebaseEnrichment,
    isBusy = false,
    searchQuery = searchQuery,
    onSearchQueryChanged = { searchQuery = it },
    onRefresh = { tenderScraperViewModel.loadFirebaseTenders() },
    listStatus = scraperUiState.firebaseListStatus,
    extraActions = {
      Button(
        onClick = { tenderScraperViewModel.removeExpiredFirebaseTenders() },
        enabled = !scraperUiState.isCleaningExpiredFirebaseTenders,
      ) {
        Text("Clean Expired")
      }
      if (scraperUiState.firebaseCleanupStatus.isNotBlank()) {
        Text(
          text = scraperUiState.firebaseCleanupStatus,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    tenderIds = filteredTenderIds,
    cardContent = { tenderId ->
      FirebaseTenderBrowserCard(
        tenderId = tenderId,
        downloadStatus = scraperUiState.firebaseDownloadStatusByTender[tenderId],
        onSync = { tenderScraperViewModel.downloadTenderFromFirebase(tenderId) },
        onViewJson = {
          bottomSheetContent = tenderScraperViewModel.getManifestContent(tenderId)
          isViewingFiles = false
          showBottomSheet = true
        },
        onViewFiles = {
          tenderFiles = tenderScraperViewModel.getTenderFiles(tenderId)
          isViewingFiles = true
          showBottomSheet = true
        },
      )
    },
  )

  FirebaseTenderBottomSheet(
    showBottomSheet = showBottomSheet,
    onDismiss = { showBottomSheet = false },
    isViewingFiles = isViewingFiles,
    bottomSheetContent = bottomSheetContent,
    tenderFiles = tenderFiles,
  )
}

@Composable
fun FirebaseTenderEnrichmentScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateToScraper: () -> Unit,
  navigateToFirebaseBrowser: () -> Unit,
) {
  val tenderScraperViewModel: TenderScraperViewModel = hiltViewModel()
  val scraperUiState by tenderScraperViewModel.uiState.collectAsState()
  val modelUiState by modelManagerViewModel.uiState.collectAsState()
  val context = LocalContext.current
  var searchQuery by remember { mutableStateOf("") }
  var showBottomSheet by remember { mutableStateOf(false) }
  var bottomSheetContent by remember { mutableStateOf("") }
  var tenderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
  var isViewingFiles by remember { mutableStateOf(false) }

  val downloadedGemmaModel =
    remember(modelUiState.modelDownloadStatus, modelUiState.tasks) {
      modelManagerViewModel.getAllDownloadedModels().firstOrNull {
        val modelName = it.displayName.ifEmpty { it.name }
        modelName.contains("gemma", ignoreCase = true)
      }
    }

  LaunchedEffect(Unit) {
    tenderScraperViewModel.loadFirebaseTenders()
  }

  val filteredTenderIds =
    remember(scraperUiState.firebaseTenderIds, searchQuery) {
      scraperUiState.firebaseTenderIds.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
    }

  FirebaseTenderScreenScaffold(
    title = "Firebase Re-Enrichment",
    currentPage = FirebaseTenderPage.ENRICHMENT,
    navigateToScraper = navigateToScraper,
    navigateToFirebaseBrowser = navigateToFirebaseBrowser,
    navigateToFirebaseEnrichment = {},
    isBusy = false,
    searchQuery = searchQuery,
    onSearchQueryChanged = { searchQuery = it },
    onRefresh = { tenderScraperViewModel.loadFirebaseTenders() },
    listStatus = scraperUiState.firebaseListStatus,
    tenderIds = filteredTenderIds,
    cardContent = { tenderId ->
      FirebaseTenderEnrichmentCard(
        tenderId = tenderId,
        downloadStatus = scraperUiState.firebaseDownloadStatusByTender[tenderId],
        enrichmentStatus = scraperUiState.gemmaEnrichmentStatusByTender[tenderId],
        uploadStatus = scraperUiState.firebaseUploadStatusByTender[tenderId],
        onSync = { tenderScraperViewModel.downloadTenderFromFirebase(tenderId) },
        onViewJson = {
          bottomSheetContent = tenderScraperViewModel.getManifestContent(tenderId)
          isViewingFiles = false
          showBottomSheet = true
        },
        onViewFiles = {
          tenderFiles = tenderScraperViewModel.getTenderFiles(tenderId)
          isViewingFiles = true
          showBottomSheet = true
        },
        onEnrichAndUpload = {
          val model = downloadedGemmaModel
          if (model == null) {
            Toast.makeText(context, "Download a Gemma model first.", Toast.LENGTH_SHORT).show()
          } else {
            tenderScraperViewModel.enrichFirebaseTender(model, tenderId)
          }
        },
      )
    },
  )

  FirebaseTenderBottomSheet(
    showBottomSheet = showBottomSheet,
    onDismiss = { showBottomSheet = false },
    isViewingFiles = isViewingFiles,
    bottomSheetContent = bottomSheetContent,
    tenderFiles = tenderFiles,
  )
}

@Composable
private fun FirebaseTenderScreenScaffold(
  title: String,
  currentPage: FirebaseTenderPage,
  navigateToScraper: () -> Unit,
  navigateToFirebaseBrowser: () -> Unit,
  navigateToFirebaseEnrichment: () -> Unit,
  isBusy: Boolean,
  searchQuery: String,
  onSearchQueryChanged: (String) -> Unit,
  onRefresh: () -> Unit,
  listStatus: String,
  extraActions: @Composable (() -> Unit)? = null,
  tenderIds: List<String>,
  cardContent: @Composable (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    FirebaseTenderNavRow(
      currentPage = currentPage,
      navigateToScraper = navigateToScraper,
      navigateToFirebaseBrowser = navigateToFirebaseBrowser,
      navigateToFirebaseEnrichment = navigateToFirebaseEnrichment,
    )

    Text(title, style = MaterialTheme.typography.headlineSmall)

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChanged,
        modifier = Modifier.weight(1f),
        label = { Text("Search tender ID") },
        singleLine = true,
      )
      Button(onClick = onRefresh) {
        Text("Refresh")
      }
    }

    if (isBusy) {
      CircularProgressIndicator()
    }

    if (listStatus.isNotBlank()) {
      Text(listStatus, style = MaterialTheme.typography.bodySmall)
    }

    extraActions?.invoke()

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (tenderIds.isEmpty()) {
        item {
          Text("No Firebase tenders match the current search.")
        }
      }
      items(tenderIds) { tenderId ->
        cardContent(tenderId)
      }
    }
  }
}

@Composable
private fun FirebaseTenderNavRow(
  currentPage: FirebaseTenderPage,
  navigateToScraper: () -> Unit,
  navigateToFirebaseBrowser: () -> Unit,
  navigateToFirebaseEnrichment: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Button(
      onClick = navigateToScraper,
      enabled = true,
      modifier = Modifier.weight(1f),
    ) {
      Text("Scraper")
    }
    Button(
      onClick = navigateToFirebaseBrowser,
      enabled = currentPage != FirebaseTenderPage.BROWSER,
      modifier = Modifier.weight(1f),
    ) {
      Text("Firebase Browse")
    }
    Button(
      onClick = navigateToFirebaseEnrichment,
      enabled = currentPage != FirebaseTenderPage.ENRICHMENT,
      modifier = Modifier.weight(1f),
    ) {
      Text("Firebase Enrich")
    }
  }
}

@Composable
private fun FirebaseTenderBrowserCard(
  tenderId: String,
  downloadStatus: String?,
  onSync: () -> Unit,
  onViewJson: () -> Unit,
  onViewFiles: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Tender ID: $tenderId")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSync, modifier = Modifier.weight(1f)) {
          Text("Sync Firebase")
        }
        Button(onClick = onViewJson, modifier = Modifier.weight(1f)) {
          Text("View JSON")
        }
      }
      Button(onClick = onViewFiles, modifier = Modifier.fillMaxWidth()) {
        Text("View Files")
      }
      if (!downloadStatus.isNullOrBlank()) {
        Text(downloadStatus, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun FirebaseTenderEnrichmentCard(
  tenderId: String,
  downloadStatus: String?,
  enrichmentStatus: String?,
  uploadStatus: String?,
  onSync: () -> Unit,
  onViewJson: () -> Unit,
  onViewFiles: () -> Unit,
  onEnrichAndUpload: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Tender ID: $tenderId")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSync, modifier = Modifier.weight(1f)) {
          Text("Sync Firebase")
        }
        Button(onClick = onViewJson, modifier = Modifier.weight(1f)) {
          Text("View JSON")
        }
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onViewFiles, modifier = Modifier.weight(1f)) {
          Text("View Files")
        }
        Button(onClick = onEnrichAndUpload, modifier = Modifier.weight(1f)) {
          Text("Enrich + Upload")
        }
      }
      if (!downloadStatus.isNullOrBlank()) {
        Text(downloadStatus, style = MaterialTheme.typography.bodySmall)
      }
      if (!enrichmentStatus.isNullOrBlank()) {
        Text(enrichmentStatus, style = MaterialTheme.typography.bodySmall)
      }
      if (!uploadStatus.isNullOrBlank()) {
        Text(uploadStatus, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirebaseTenderBottomSheet(
  showBottomSheet: Boolean,
  onDismiss: () -> Unit,
  isViewingFiles: Boolean,
  bottomSheetContent: String,
  tenderFiles: List<File>,
) {
  val context = LocalContext.current
  if (!showBottomSheet) {
    return
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    if (isViewingFiles) {
      LazyColumn(modifier = Modifier.padding(16.dp)) {
        items(tenderFiles) { file ->
          TextButton(
            onClick = {
              if (file.extension.lowercase() == "json" || file.extension.lowercase() == "txt") {
                return@TextButton
              }

              val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file,
              )
              val mimeType =
                when (file.extension.lowercase()) {
                  "pdf" -> "application/pdf"
                  "json" -> "application/json"
                  "txt" -> "text/plain"
                  else -> "*/*"
                }
              val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              val chooser = Intent.createChooser(intent, "Open ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(chooser)
              } else {
                Toast.makeText(context, "No app available to open ${file.name}", Toast.LENGTH_SHORT)
                  .show()
              }
            },
          ) {
            Text(file.name)
          }
        }
      }
    } else {
      SelectionContainer {
        Text(
          bottomSheetContent,
          modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        )
      }
    }
  }
}
