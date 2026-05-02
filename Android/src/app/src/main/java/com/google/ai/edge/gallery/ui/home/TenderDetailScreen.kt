package com.google.ai.edge.gallery.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val DetailGold = Color(0xFFC5A059)
private val DetailDarkPage = Color(0xFF2F3948)
private val DetailDarkInk = Color(0xFFD6DEE8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TenderDetailScreen(
  tenderId: String,
  title: String,
  manifestFile: File,
  tenderFiles: List<File>,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val pdfFiles = remember(tenderFiles) { tenderFiles.filter { it.extension.equals("pdf", ignoreCase = true) } }
  val isDarkTheme = isSystemInDarkTheme()
  val context = LocalContext.current

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      GalleryTopAppBar(
        title = "",
        leftAction =
          AppBarAction(
            actionType = AppBarActionType.NAVIGATE_UP,
            actionFn = onBack,
          ),
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = {
          if (!manifestFile.exists()) {
            Toast.makeText(context, "Manifest file not found", Toast.LENGTH_SHORT).show()
            return@FloatingActionButton
          }

          val manifestUri =
            FileProvider.getUriForFile(
              context,
              "${context.packageName}.provider",
              manifestFile,
            )
          val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
              type = "application/json"
              putExtra(Intent.EXTRA_STREAM, manifestUri)
              putExtra(Intent.EXTRA_SUBJECT, "$tenderId manifest")
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
          context.startActivity(Intent.createChooser(shareIntent, "Share manifest"))
        },
        containerColor = DetailGold,
        contentColor = Color(0xFF1A1A1A),
      ) {
        Icon(imageVector = Icons.Rounded.Share, contentDescription = "Share manifest")
      }
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = innerPadding.calculateTopPadding() + 12.dp, bottom = innerPadding.calculateBottomPadding() + 88.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        Card(
          shape = RoundedCornerShape(28.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
          Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = tenderId,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = title,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      item {
        Text(
          text = "PDF Preview",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )
      }

      if (pdfFiles.isEmpty()) {
        item {
          EmptyPdfState()
        }
      } else {
        itemsIndexed(pdfFiles, key = { _, file -> file.absolutePath }) { _, pdfFile ->
          PdfPreviewerCard(
            pdfFile = pdfFile,
            isDarkTheme = isDarkTheme,
          )
        }
      }
    }
  }
}

@Composable
private fun PdfPreviewerCard(
  pdfFile: File,
  isDarkTheme: Boolean,
  modifier: Modifier = Modifier,
) {
  var pageBitmaps by remember(pdfFile.absolutePath) { mutableStateOf<List<Bitmap>>(emptyList()) }
  var isLoading by remember(pdfFile.absolutePath) { mutableStateOf(true) }
  var errorMessage by remember(pdfFile.absolutePath) { mutableStateOf<String?>(null) }

  LaunchedEffect(pdfFile.absolutePath) {
    isLoading = true
    errorMessage = null
    val renderedPages = runCatching { renderPdfPreviewBitmaps(pdfFile) }
    renderedPages
      .onSuccess { bitmaps ->
        pageBitmaps = bitmaps
      }
      .onFailure { error ->
        pageBitmaps = emptyList()
        errorMessage = error.message ?: "Unable to render PDF preview"
      }
    isLoading = false
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = pdfFile.name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      when {
        isLoading -> {
          Box(
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(color = DetailGold)
          }
        }
        errorMessage != null -> {
          Text(
            text = errorMessage.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }
        else -> {
          pageBitmaps.forEachIndexed { pageIndex, bitmap ->
            Card(
              shape = RoundedCornerShape(18.dp),
              colors = CardDefaults.cardColors(containerColor = if (isDarkTheme) DetailDarkPage else Color.White),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = "Page ${pageIndex + 1}",
                  style = MaterialTheme.typography.labelMedium,
                  color = if (isDarkTheme) DetailDarkInk else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                  bitmap = bitmap.asImageBitmap(),
                  contentDescription = "Preview of ${pdfFile.name} page ${pageIndex + 1}",
                  modifier = Modifier.fillMaxWidth().background(if (isDarkTheme) DetailDarkPage else Color.White),
                  colorFilter = if (isDarkTheme) darkModePdfColorFilter() else null,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EmptyPdfState() {
  Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.PictureAsPdf,
        contentDescription = null,
        tint = DetailGold,
        modifier = Modifier.size(32.dp),
      )
      Text(
        text = "No PDF attachments available for preview.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

private suspend fun renderPdfPreviewBitmaps(file: File): List<Bitmap> = withContext(Dispatchers.IO) {
  val renderedPages = mutableListOf<Bitmap>()
  var descriptor: ParcelFileDescriptor? = null
  var renderer: PdfRenderer? = null

  try {
    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    renderer = PdfRenderer(descriptor)
    val pageCount = minOf(renderer.pageCount, 6)

    for (pageIndex in 0 until pageCount) {
      val page = renderer.openPage(pageIndex)
      try {
        val targetWidth = 1240
        val scale = targetWidth.toFloat() / page.width.toFloat()
        val bitmap = Bitmap.createBitmap(targetWidth, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(AndroidColor.WHITE)
        val matrix = Matrix().apply { postScale(scale, scale) }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        renderedPages += bitmap
      } finally {
        page.close()
      }
    }
  } finally {
    renderer?.close()
    descriptor?.close()
  }

  renderedPages
}

private fun darkModePdfColorFilter(): ColorFilter {
  val darkBackground = DetailDarkPage
  val lightInk = DetailDarkInk
  return ColorFilter.colorMatrix(
    ColorMatrix(
      floatArrayOf(
        darkBackground.red - lightInk.red,
        0f,
        0f,
        0f,
        lightInk.red * 255f,
        0f,
        darkBackground.green - lightInk.green,
        0f,
        0f,
        lightInk.green * 255f,
        0f,
        0f,
        darkBackground.blue - lightInk.blue,
        0f,
        lightInk.blue * 255f,
        0f,
        0f,
        0f,
        1f,
        0f,
      ),
    ),
  )
}