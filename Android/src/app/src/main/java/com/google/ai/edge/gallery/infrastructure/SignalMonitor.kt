package com.google.ai.edge.gallery.infrastructure

import android.util.Log
import com.google.gson.JsonElement
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "AGSignalMonitor"

data class SignalMonitorConfig(
  val routerConfig: RouterApiConfig,
  val pollIntervalMs: Long = 30_000,
  val sinrStatusPath: String = "/api/net/status",
  val sinrJsonKeys: List<String> = listOf("sinr", "SINR", "sinrDb", "sinr_db"),
  val minimumSinrDb: Double = 5.0,
  val speedTestUrl: String = "https://speed.hetzner.de/100MB.bin",
  val minimumDownloadMbps: Double = 20.0,
  val speedProbeBytes: Long = 262_144,
  val minimumBlinkIntervalMs: Long = 90_000,
)

data class SignalMonitorSnapshot(
  val sinrDb: Double?,
  val estimatedDownloadMbps: Double?,
  val triggeredBlink: Boolean,
  val reason: String?,
)

class SignalMonitor(
  private val config: SignalMonitorConfig,
  private val routerManager: RouterManager = RouterManager(config = config.routerConfig),
  private val client: OkHttpClient = defaultHttpClient(),
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
  private val blinkMutex = Mutex()
  private var monitorJob: Job? = null
  private var lastBlinkTimestampMs: Long = 0L

  fun start() {
    if (monitorJob?.isActive == true) {
      return
    }

    monitorJob =
      scope.launch {
        Log.d(TAG, "Signal monitor started")
        while (isActive) {
          runCatching { checkNow() }
            .onFailure { error -> Log.w(TAG, "Signal monitor iteration failed", error) }
          delay(config.pollIntervalMs)
        }
      }
  }

  suspend fun checkNow(): SignalMonitorSnapshot {
    val sinrDb = fetchSinrDb()
    val estimatedDownloadMbps = estimateDownloadMbps()
    val reason = buildTriggerReason(sinrDb = sinrDb, estimatedDownloadMbps = estimatedDownloadMbps)

    if (reason == null) {
      return SignalMonitorSnapshot(
        sinrDb = sinrDb,
        estimatedDownloadMbps = estimatedDownloadMbps,
        triggeredBlink = false,
        reason = null,
      )
    }

    val triggeredBlink = triggerBlinkIfNeeded(reason)
    return SignalMonitorSnapshot(
      sinrDb = sinrDb,
      estimatedDownloadMbps = estimatedDownloadMbps,
      triggeredBlink = triggeredBlink,
      reason = reason,
    )
  }

  fun stop() {
    monitorJob?.cancel()
    monitorJob = null
    Log.d(TAG, "Signal monitor stopped")
  }

  fun close() {
    stop()
    scope.cancel()
  }

  private suspend fun fetchSinrDb(): Double? {
    val statusJson = routerManager.fetchJson(config.sinrStatusPath)
    val sinrDb = findNumericValue(statusJson, config.sinrJsonKeys)
    Log.d(TAG, "Fetched SINR reading: $sinrDb dB")
    return sinrDb
  }

  private fun estimateDownloadMbps(): Double? {
    val probeUrl = config.speedTestUrl.toHttpUrl()
    val startNanos = System.nanoTime()
    val response =
      client
        .newCall(
          Request.Builder()
            .url(probeUrl)
            .head()
            .header("Range", "bytes=0-${config.speedProbeBytes - 1}")
            .build()
        )
        .execute()

    response.use {
      if (!it.isSuccessful) {
        throw IOException(
          "Speed probe failed: HEAD ${probeUrl} -> ${it.code} ${it.message}"
        )
      }

      val durationSeconds = ((System.nanoTime() - startNanos).coerceAtLeast(1L)).toDouble() / 1_000_000_000.0
      val contentLength =
        it.header("Content-Length")?.toLongOrNull()?.coerceAtMost(config.speedProbeBytes)
          ?: return null.also { Log.w(TAG, "Speed probe did not return Content-Length") }

      val megabits = (contentLength * 8.0) / 1_000_000.0
      val estimatedMbps = megabits / durationSeconds
      Log.d(TAG, "Estimated download speed: $estimatedMbps Mbps")
      return estimatedMbps
    }
  }

  private fun buildTriggerReason(sinrDb: Double?, estimatedDownloadMbps: Double?): String? {
    if (sinrDb != null && sinrDb < config.minimumSinrDb) {
      return "SINR dropped to $sinrDb dB below ${config.minimumSinrDb} dB"
    }
    if (estimatedDownloadMbps != null && estimatedDownloadMbps < config.minimumDownloadMbps) {
      return "Estimated download speed dropped to $estimatedDownloadMbps Mbps below ${config.minimumDownloadMbps} Mbps"
    }
    return null
  }

  private suspend fun triggerBlinkIfNeeded(reason: String): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastBlinkTimestampMs < config.minimumBlinkIntervalMs) {
      Log.d(TAG, "Skipping blink during cooldown window. reason=$reason")
      return false
    }

    return blinkMutex.withLock {
      val currentNow = System.currentTimeMillis()
      if (currentNow - lastBlinkTimestampMs < config.minimumBlinkIntervalMs) {
        Log.d(TAG, "Skipping blink during cooldown window after lock. reason=$reason")
        return@withLock false
      }

      Log.w(TAG, "Signal watchdog triggering router blink. reason=$reason")
      routerManager.blinkConnection()
      lastBlinkTimestampMs = System.currentTimeMillis()
      true
    }
  }

  private fun findNumericValue(element: JsonElement, candidateKeys: List<String>): Double? {
    if (element.isJsonNull) {
      return null
    }

    if (element.isJsonObject) {
      val jsonObject = element.asJsonObject
      for (candidateKey in candidateKeys) {
        jsonObject.get(candidateKey)?.let { value ->
          extractDouble(value)?.let { return it }
        }
      }
      for ((_, value) in jsonObject.entrySet()) {
        findNumericValue(value, candidateKeys)?.let { return it }
      }
    }

    if (element.isJsonArray) {
      for (item in element.asJsonArray) {
        findNumericValue(item, candidateKeys)?.let { return it }
      }
    }

    return null
  }

  private fun extractDouble(element: JsonElement): Double? {
    return runCatching {
        when {
          element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asDouble
          element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString.toDouble()
          else -> null
        }
      }
      .getOrNull()
  }

  companion object {
    private fun defaultHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
  }
}