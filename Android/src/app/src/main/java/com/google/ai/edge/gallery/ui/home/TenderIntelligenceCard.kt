package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

private val IntelligenceGold = Color(0xFFC5A059)

@Composable
fun TenderIntelligenceCard(
  tenderId: String,
  title: String,
  modifier: Modifier = Modifier,
  statusLabel: String = "Spec Found",
  onClick: () -> Unit,
) {
  val cardShape = RoundedCornerShape(24.dp)

  ElevatedCard(
    onClick = onClick,
    modifier = modifier.fillMaxWidth().border(width = 1.dp, color = IntelligenceGold, shape = cardShape),
    shape = cardShape,
    colors =
      CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.surface,
      ),
    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
  ) {
    Column(
      modifier = Modifier.padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        Surface(
          shape = CircleShape,
          color = IntelligenceGold.copy(alpha = 0.16f),
          contentColor = IntelligenceGold,
        ) {
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = tenderId,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        AssistChip(
          onClick = onClick,
          label = {
            Text(
              text = "Enriched by Gemma",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          leadingIcon = {
            androidx.compose.material3.Icon(
              imageVector = Icons.Rounded.AutoAwesome,
              contentDescription = null,
            )
          },
          colors =
            AssistChipDefaults.assistChipColors(
              containerColor = IntelligenceGold.copy(alpha = 0.12f),
              labelColor = MaterialTheme.colorScheme.onSurface,
              leadingIconContentColor = IntelligenceGold,
            ),
          border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = IntelligenceGold.copy(alpha = 0.65f),
          ),
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun TenderIntelligenceCardPreview() {
  GalleryTheme {
    TenderIntelligenceCard(
      tenderId = "E1026BMSERI",
      title = "The review, design, supply, refurbishment, modification, installation and commissioning of the lube oil system.",
      statusLabel = "Spec Found",
      modifier = Modifier.padding(16.dp),
      onClick = {},
    )
  }
}