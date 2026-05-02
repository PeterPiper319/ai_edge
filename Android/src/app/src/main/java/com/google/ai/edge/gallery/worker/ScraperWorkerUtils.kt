package com.google.ai.edge.gallery.worker

import androidx.work.Constraints
import androidx.work.NetworkType

fun getScraperConstraints(): Constraints {
  return Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresCharging(true)
    .build()
}
