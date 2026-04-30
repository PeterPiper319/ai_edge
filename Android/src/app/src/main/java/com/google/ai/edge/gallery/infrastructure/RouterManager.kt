package com.google.ai.edge.gallery.infrastructure

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup

private const val TAG = "AGRouterManager"
const val ROUTER_USERNAME_SECRET_KEY = "native_skill___router_username"
const val ROUTER_PASSWORD_SECRET_KEY = "native_skill___router_password"

data class RouterApiConfig(
  val baseUrl: String = "http://192.168.0.1",
  val username: String = "admin",
  val password: String,
  val luciBasePath: String = "/cgi-bin/luci",
  val luciRootPath: String = "/cgi-bin/luci",
  val loginPath: String = "/cgi-bin/luci/login/action_login",
  val ubusPath: String = "/ubus/",
  val ubusObject: String = "network",
  val ubusFlag: String = "set_wifi_switch",
  val wirelessConfigName: String = "wireless",
  val candidateWirelessSections: List<String> = listOf("radio0", "radio1", "default_radio0", "default_radio1", "ap0", "ap1"),
  val uciCommand: String = "/sbin/uci",
  val uciCommandCandidates: List<String> = listOf("/sbin/uci", "/bin/uci", "uci"),
  val shellCommand: String = "/bin/sh",
  val wifiCommand: String = "/sbin/wifi",
  val wifiCommandCandidates: List<String> = listOf("/sbin/wifi", "/bin/wifi", "wifi"),
  val towerLockPath: String = "/admin/network/tower-lock",
  val towerLockField: String = "pci",
)

class RouterManager(
  private val config: RouterApiConfig,
  private val cookieJar: PersistentCookieJar = PersistentCookieJar(),
  private val client: OkHttpClient = defaultHttpClient(cookieJar),
  private val gson: Gson = Gson(),
) {
  private var stok: String? = null
  private var sysauthCookie: String? = null

  private data class UbusResponse(
    val response: Response,
    val bodyText: String,
    val json: JsonObject?,
  )

  private data class LoginPageSnapshot(
    val token: String?,
    val html: String,
    val locationHeader: String,
  )

  suspend fun blinkConnection() =
    withContext(Dispatchers.IO) {
      Log.d(TAG, "Blink connection started")
      login()
      callWifiSwitch(state = 0, autoRestoreDelayMillis = 3_000)
      Log.d(TAG, "Blink connection completed")
    }

  suspend fun lockToTower(pci: String) =
    withContext(Dispatchers.IO) {
      require(pci.isNotBlank()) { "pci must not be blank" }
      login()
      executeLuciCommand(config.towerLockPath)
    }

  suspend fun fetchJson(path: String): JsonObject =
    withContext(Dispatchers.IO) {
      login()
      val response = execute(getRequest(executeLuciCommand(path), useAbsoluteUrl = true))
      val bodyText = response.body.string()
      response.close()
      JsonParser.parseString(bodyText).asJsonObject
    }

  private fun login() {
    val normalizedPassword = config.password.trim()
    cookieJar.clear()
    sysauthCookie = null
    stok = null

    val loginPage = fetchLoginPageSnapshot()
    if (!loginPage.token.isNullOrBlank()) {
      attemptTokenLogin(token = loginPage.token, normalizedPassword = normalizedPassword)
    } else {
      Log.d(TAG, "LuCI token missing after HTML dump. Trying direct handshake fallback.")
      attemptDirectHandshake(normalizedPassword)
    }

    if (sysauthCookie.isNullOrBlank()) {
      Log.d(TAG, "LuCI session cookie still missing. Trying ubus login fallback.")
      attemptUbusLogin(normalizedPassword)
    }

    require(!stok.isNullOrBlank()) {
      "Router login did not produce a usable session token from ${config.baseUrl}${config.loginPath}. " +
        "sysauthPresent=${!sysauthCookie.isNullOrBlank()} html=${loginPage.html.take(200)}"
    }

    Log.d(
      TAG,
      "Router login succeeded. stok=$stok sysauthPresent=${!sysauthCookie.isNullOrBlank()}",
    )
  }

  private fun fetchLoginPageSnapshot(): LoginPageSnapshot {
    val request =
      Request.Builder()
        .url(resolveUrl(config.luciRootPath))
        .get()
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Origin", config.baseUrl)
        .header("Referer", config.baseUrl)
        .build()

    val response = executeAllowingRedirects(request)
    val html = response.body.string()
    logResponseDetails("scrape", response)
    Log.d("ROUTER_HTML", html)
    val locationHeader = response.header("Location").orEmpty()
    response.close()

    val document = Jsoup.parse(html)
    val tokenFromInput =
      document.selectFirst("input[name=token]")?.attr("value")?.takeIf { it.isNotBlank() }
    tokenFromInput?.let {
      Log.d(TAG, "Found LuCI token in hidden input")
    }

    val tokenFromScript =
      Regex("(?:var|let|const)\\s+token\\s*=\\s*['\"]([^'\"]+)['\"]")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
    tokenFromScript?.let {
      Log.d(TAG, "Found LuCI token in JavaScript variable")
    }

    return LoginPageSnapshot(
      token = tokenFromInput ?: tokenFromScript,
      html = html,
      locationHeader = locationHeader,
    )
  }

  private fun attemptTokenLogin(token: String, normalizedPassword: String) {
    val hashedPassword = sha256Hex("$token:$normalizedPassword")
    val requestBody =
      FormBody.Builder()
        .add("luci_username", config.username)
        .add("luci_password", hashedPassword)
        .add("token", token)
        .build()

    val request =
      Request.Builder()
        .url(resolveUrl(config.loginPath))
        .post(requestBody)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Origin", config.baseUrl)
        .header("Referer", "${config.baseUrl}${config.luciRootPath}")
        .header("X-Requested-With", "XMLHttpRequest")
        .build()

    val response = executeAllowingRedirects(request)
    val bodyText = response.body.string()
    val locationHeader = response.header("Location").orEmpty()
    val redirectTarget = locationHeader.ifBlank { bodyText }
    logResponseDetails("login", response)
    response.close()
    captureSessionArtifacts(
      sourceUrl = resolveUrl(config.loginPath),
      locationHeader = locationHeader,
      responseBody = bodyText,
      setCookieHeaders = response.headers("Set-Cookie"),
    )

    if (sysauthCookie.isNullOrBlank() && locationHeader.isNotBlank()) {
      val redirectUrl = resolveRedirectUrl(locationHeader)
      Log.d(TAG, "Following LuCI redirect to finalize session: $redirectUrl")
      val redirectResponse = executeAllowingRedirects(buildDashboardRequest(redirectUrl))
      logResponseDetails("redirect", redirectResponse)
      redirectResponse.close()
      sysauthCookie =
        cookieJar.findCookieValue(redirectUrl, "sysauth")
          ?: cookieJar.findCookieValue(config.baseUrl.toHttpUrl(), "sysauth")
          ?: sysauthCookie
    }
  }

  private fun attemptDirectHandshake(normalizedPassword: String) {
    val directLoginUrl =
      config.baseUrl
        .toHttpUrl()
        .newBuilder()
        .encodedPath("/cgi-bin/luci/login/action_login")
        .addQueryParameter("flag", "action_login")
        .build()

    val requestBody =
      FormBody.Builder()
        .add("luci_username", config.username)
        .add("luci_password", normalizedPassword)
        .build()

    val request =
      Request.Builder()
        .url(directLoginUrl)
        .post(requestBody)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; S23 Ultra)")
        .header("Referer", "http://192.168.0.1/cgi-bin/luci")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,/;q=0.8")
        .build()

    val response = executeAllowingRedirects(request)
    val bodyText = response.body.string()
    val locationHeader = response.header("Location").orEmpty()
    logResponseDetails("direct-login", response)
    response.close()
    captureSessionArtifacts(
      sourceUrl = resolveUrl(config.loginPath),
      locationHeader = locationHeader,
      responseBody = bodyText,
      setCookieHeaders = response.headers("Set-Cookie"),
    )
    Log.d(
      TAG,
      "Direct handshake result code=${response.code} redirect=${response.code in 300..399} sysauthPresent=${!sysauthCookie.isNullOrBlank()}",
    )
  }

  private fun attemptUbusLogin(normalizedPassword: String) {
    val requestBody =
      gson
        .toJson(
          mapOf(
            "jsonrpc" to "2.0",
            "id" to 1,
            "method" to "call",
            "params" to
              listOf(
                "00000000000000000000000000000000",
                "session",
                "login",
                mapOf("username" to config.username, "password" to normalizedPassword),
              ),
          ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

    val request =
      Request.Builder()
        .url(resolveUrl("/ubus"))
        .post(requestBody)
        .header("Accept", "application/json")
        .build()

    val response = executeAllowingRedirects(request)
    val bodyText = response.body.string()
    logResponseDetails("ubus-login", response)
    response.close()

    runCatching {
        JsonParser.parseString(bodyText).asJsonObject
      }
      .getOrNull()
      ?.getAsJsonArray("result")
      ?.get(1)
      ?.asJsonObject
      ?.get("ubus_rpc_session")
      ?.asString
      ?.takeIf { it.isNotBlank() }
      ?.let { sessionToken ->
        stok = sessionToken
        Log.d(TAG, "Ubus login succeeded. Saved ubus_rpc_session as stok.")
      }
  }

  private fun captureSessionArtifacts(
    sourceUrl: okhttp3.HttpUrl,
    locationHeader: String,
    responseBody: String,
    setCookieHeaders: List<String>,
  ) {
    val redirectTarget = locationHeader.ifBlank { responseBody }
    sysauthCookie =
      extractSysauthCookie(setCookieHeaders)
        ?: cookieJar.findCookieValue(sourceUrl, "sysauth")
        ?: cookieJar.findCookieValue(config.baseUrl.toHttpUrl(), "sysauth")
        ?: sysauthCookie
    stok = extractStok(redirectTarget) ?: extractStok(locationHeader) ?: stok
  }

  fun extractStok(url: String): String? {
    return Regex(";stok=([A-Za-z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)
  }

  private fun extractSysauthCookie(setCookieHeaders: List<String>): String? {
    return setCookieHeaders
      .asSequence()
      .map { it.substringBefore(';') }
      .firstOrNull { it.startsWith("sysauth=") }
  }

  private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  fun executeLuciCommand(path: String): String {
    val currentStok = requireNotNull(stok) { "No LuCI stok available. Call login() first." }
    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    return "${config.baseUrl}${config.luciBasePath}/;stok=$currentStok$normalizedPath"
  }

  private fun dashboardUrl(): String = executeLuciCommand("/admin/status")

  private fun buildDashboardRequest(url: okhttp3.HttpUrl): Request {
    return Request.Builder()
      .url(url)
      .get()
      .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      .header("Referer", "${config.baseUrl}${config.luciBasePath}")
      .header("Origin", config.baseUrl)
      .header("User-Agent", "Mozilla/5.0 (Android) EdgeGallery/1.0")
      .build()
  }

  private fun logResponseDetails(label: String, response: Response) {
    Log.d(TAG, "Router $label response code=${response.code}")
    for ((name, value) in response.headers) {
      Log.d(TAG, "Router $label header $name=$value")
    }
  }

  private fun resolveRedirectUrl(locationHeader: String): okhttp3.HttpUrl {
    return when {
      locationHeader.startsWith("http://") || locationHeader.startsWith("https://") ->
        locationHeader.toHttpUrl()
      locationHeader.startsWith("/") ->
        config.baseUrl.toHttpUrl().newBuilder().encodedPath(locationHeader).build()
      else ->
        config.baseUrl.toHttpUrl().newBuilder().encodedPath("/$locationHeader").build()
    }
  }

  private fun callWifiSwitch(state: Int, autoRestoreDelayMillis: Long? = null): Boolean {
    val currentStok = requireNotNull(stok) { "No LuCI stok available. Call login() first." }
    Log.d(
      TAG,
      "Calling ubus wifi switch with state=$state stok=$currentStok object=${config.ubusObject} method=${config.ubusFlag} sysauthPresent=${!sysauthCookie.isNullOrBlank()} url=${resolveUrl(config.ubusPath)}",
    )

    val ubusResponse =
      callUbus(
        objectName = config.ubusObject,
        methodName = config.ubusFlag,
        parameters = mapOf("state" to state),
        label = "ubus-switch",
      )

    val errorObject = ubusResponse.json?.getAsJsonObject("error")
    if (errorObject != null) {
      val errorCode = errorObject.get("code")?.asInt
      val errorMessage = errorObject.get("message")?.asString.orEmpty()
      Log.e(
        TAG,
        "Ubus switch returned JSON-RPC error code=$errorCode message=$errorMessage body=${ubusResponse.bodyText}",
      )
      if (errorCode == -32002) {
        probeUbusAccess(currentStok)
        if (state == 0 && autoRestoreDelayMillis != null) {
          val scheduled = scheduleRouterSideWifiBounce(autoRestoreDelayMillis)
          if (scheduled) {
            return true
          }
          probeNonDestructiveFileExecCommands()
          throw IOException(
            "Router cannot safely schedule Wi-Fi recovery after switch-off. Refusing to disable Wi-Fi because recovery is not guaranteed. Check AGRouterManager logs for ubus-file-exec-wifi-bounce details."
          )
        }
        performUciWifiSwitch(state)
        return false
      }
      throw IOException(
        "Router ubus switch failed with JSON-RPC error ${errorCode ?: "unknown"}: ${errorMessage.ifBlank { "Unknown error" }}. Check AGRouterManager logs for the response body."
      )
    }

    val resultCode = ubusResponse.json?.getAsJsonArray("result")?.get(0)?.asInt
    if (resultCode != null && resultCode != 0) {
      Log.e(TAG, "Ubus switch returned non-zero result=$resultCode body=${ubusResponse.bodyText}")
      throw IOException("Router ubus switch failed with result code $resultCode. Check AGRouterManager logs for the response body.")
    }
    Log.d(TAG, "Ubus switch response body: ${ubusResponse.bodyText}")
    return false
  }

  private fun scheduleRouterSideWifiBounce(autoRestoreDelayMillis: Long): Boolean {
    Log.d(
      TAG,
      "Attempting single-command router-side Wi-Fi restart because shell-based delayed bounce is rejected. requestedDelayMs=$autoRestoreDelayMillis",
    )

    val bounceArgumentCandidates =
      listOf(
        listOf("reload"),
        listOf("restart"),
        emptyList(),
      )

    for ((index, params) in bounceArgumentCandidates.withIndex()) {
      val description = if (params.isEmpty()) "wifi" else "wifi ${params.joinToString(" ")}"

      for ((candidateIndex, commandCandidate) in config.wifiCommandCandidates.distinct().withIndex()) {
        val label = "ubus-file-exec-wifi-bounce-${index + 1}-${candidateIndex + 1}"
        val response =
          try {
            callUbus(
              objectName = "file",
              methodName = "exec",
              parameters = mapOf("command" to commandCandidate, "params" to params),
              label = label,
            )
          } catch (exception: IOException) {
            Log.d(
              TAG,
              "Router-side Wi-Fi bounce command '$commandCandidate ${params.joinToString(" ")}' likely interrupted connectivity before a response could be read. Treating this as probable success. error=${exception.message}",
            )
            return true
          }

        if (isSuccessfulUbusCall(response)) {
          Log.d(
            TAG,
            "Router-side Wi-Fi bounce command succeeded with '$commandCandidate ${params.joinToString(" ")}'. body=${response.bodyText}",
          )
          return true
        }

        Log.d(
          TAG,
          "Router-side Wi-Fi bounce candidate failed for '$commandCandidate ${params.joinToString(" ")}'. body=${response.bodyText}",
        )
      }
    }

    Log.d(TAG, "Router-side wifi bounce scheduling failed: no single-command Wi-Fi restart candidate succeeded")
    return false
  }

  private fun performUciWifiSwitch(state: Int) {
    val disabledValue = if (state == 0) "1" else "0"
    val wirelessDevices = getWirelessDeviceSections().ifEmpty { discoverWirelessSections() }
    require(wirelessDevices.isNotEmpty()) {
      "No wireless sections could be discovered for config '${config.wirelessConfigName}'."
    }

    Log.d(TAG, "Falling back to UCI wifi switch for devices=$wirelessDevices disabled=$disabledValue")

    for (deviceSection in wirelessDevices) {
      val setResponse =
        callUbus(
          objectName = "uci",
          methodName = "set",
          parameters =
            mapOf(
              "config" to config.wirelessConfigName,
              "section" to deviceSection,
              "values" to mapOf("disabled" to disabledValue),
            ),
          label = "ubus-uci-set-$deviceSection",
        )
      ensureSuccessfulUbusCall(setResponse, "uci.set($deviceSection)")
    }

    val commitResponse =
      callUbus(
        objectName = "uci",
        methodName = "commit",
        parameters = mapOf("config" to config.wirelessConfigName),
        label = "ubus-uci-commit",
      )

    val wifiCommandArgument = if (state == 0) "down" else "up"

    if (hasJsonRpcError(commitResponse, code = -32002)) {
      Log.d(
        TAG,
        "uci.commit(${config.wirelessConfigName}) was denied over ubus. Falling back to direct file.exec commands.",
      )
      val commitExecResult =
        executeFileCommandWithCandidates(
          commandCandidates = config.uciCommandCandidates,
          params = listOf("commit", config.wirelessConfigName),
          labelPrefix = "ubus-file-exec-uci-commit",
          actionDescription = "commit ${config.wirelessConfigName}",
        )
      val commitSucceeded = isSuccessfulUbusCall(commitExecResult.response)
      if (!commitSucceeded) {
        Log.d(
          TAG,
          "Direct UCI commit did not succeed. Proceeding with wifi command fallback anyway because the immediate goal is to blink connectivity.",
        )
      }

      val wifiExecResult =
        executeFileCommandWithCandidates(
          commandCandidates = config.wifiCommandCandidates,
          params = listOf(wifiCommandArgument),
          labelPrefix = "ubus-file-exec-wifi-$wifiCommandArgument",
          actionDescription = wifiCommandArgument,
        )
      ensureSuccessfulUbusCall(
        wifiExecResult.response,
        "file.exec(${wifiExecResult.command} $wifiCommandArgument)",
      )
      Log.d(TAG, "Direct UCI commit response body: ${commitExecResult.response.bodyText}")
      Log.d(TAG, "Direct wifi apply response body: ${wifiExecResult.response.bodyText}")
      Log.d(TAG, "UCI wifi switch fallback completed for state=$state via direct file.exec commands")
      return
    }

    ensureSuccessfulUbusCall(commitResponse, "uci.commit(${config.wirelessConfigName})")

    Log.d(TAG, "Applying wireless changes with candidate wifi commands ${config.wifiCommandCandidates} arg=$wifiCommandArgument")
    val execResult =
      executeFileCommandWithCandidates(
        commandCandidates = config.wifiCommandCandidates,
        params = listOf(wifiCommandArgument),
        labelPrefix = "ubus-file-exec",
        actionDescription = wifiCommandArgument,
      )
    ensureSuccessfulUbusCall(execResult.response, "file.exec(${execResult.command} $wifiCommandArgument)")
    Log.d(TAG, "Wireless apply response body: ${execResult.response.bodyText}")

    Log.d(TAG, "UCI wifi switch fallback completed for state=$state")
  }

  private fun getWirelessDeviceSections(): List<String> {
    val response =
      callUbus(
        objectName = "luci-rpc",
        methodName = "getWirelessDevices",
        parameters = emptyMap(),
        label = "ubus-luci-rpc-wireless-devices",
      )
    ensureSuccessfulUbusCall(response, "luci-rpc.getWirelessDevices")

    val resultPayload = response.secondResultPayload()
    if (resultPayload == null || !resultPayload.isJsonObject) {
      Log.e(TAG, "Wireless device payload missing or not an object: ${response.bodyText}")
      return emptyList()
    }

    return resultPayload.asJsonObject.keySet().toList()
  }

  private fun discoverWirelessSections(): List<String> {
    val configResponse =
      callUbus(
        objectName = "uci",
        methodName = "get",
        parameters = mapOf("config" to config.wirelessConfigName),
        label = "ubus-uci-get-wireless-config",
      )

    val configError = configResponse.json?.getAsJsonObject("error")
    if (configError == null) {
      val discoveredFromConfig = extractWirelessSectionsFromConfig(configResponse.secondResultPayload())
      if (discoveredFromConfig.isNotEmpty()) {
        Log.d(TAG, "Discovered wireless sections via full UCI config: $discoveredFromConfig")
        return discoveredFromConfig.distinct()
      }
      Log.d(TAG, "Full wireless UCI config was readable but did not expose section names directly: ${configResponse.bodyText}")
    } else {
      Log.d(TAG, "Full wireless UCI config lookup failed: ${configResponse.bodyText}")
    }

    val discoveredSections = mutableListOf<String>()

    for (sectionName in config.candidateWirelessSections) {
      val response =
        callUbus(
          objectName = "uci",
          methodName = "get",
          parameters = mapOf("config" to config.wirelessConfigName, "section" to sectionName),
          label = "ubus-uci-get-$sectionName",
        )

      val errorObject = response.json?.getAsJsonObject("error")
      if (errorObject != null) {
        Log.d(TAG, "Skipping wireless candidate section=$sectionName because uci.get returned ${response.bodyText}")
        continue
      }

      val payload = response.secondResultPayload()
      if (payload != null && payload.isJsonObject) {
        discoveredSections += sectionName
      }
    }

    Log.d(TAG, "Discovered wireless sections via UCI candidates: $discoveredSections")
    return discoveredSections.distinct()
  }

  private fun extractWirelessSectionsFromConfig(payload: JsonElement?): List<String> {
    if (payload == null || !payload.isJsonObject) {
      return emptyList()
    }

    val sections = mutableListOf<String>()
    collectWirelessSections(payload.asJsonObject, sections)
    return sections.distinct()
  }

  private fun collectWirelessSections(node: JsonObject, sections: MutableList<String>) {
    for ((key, value) in node.entrySet()) {
      if (!value.isJsonObject) {
        continue
      }

      val childObject = value.asJsonObject
      val sectionType =
        when {
          childObject.has(".type") && childObject.get(".type").isJsonPrimitive -> childObject.get(".type").asString
          childObject.has("type") && childObject.get("type").isJsonPrimitive -> childObject.get("type").asString
          else -> null
        }

      if (sectionType == "wifi-device" || sectionType == "wifi-iface") {
        sections += key
      }

      collectWirelessSections(childObject, sections)
    }
  }

  private fun ensureSuccessfulUbusCall(response: UbusResponse, actionLabel: String) {
    Log.d(TAG, "$actionLabel response body: ${response.bodyText}")

    val errorObject = response.json?.getAsJsonObject("error")
    if (errorObject != null) {
      val errorCode = errorObject.get("code")?.asInt
      val errorMessage = errorObject.get("message")?.asString.orEmpty()
      throw IOException(
        "$actionLabel failed with JSON-RPC error ${errorCode ?: "unknown"}: ${errorMessage.ifBlank { "Unknown error" }}. Check AGRouterManager logs for the response body."
      )
    }

    val resultCode = response.firstResultCode()
    if (resultCode != null && resultCode != 0) {
      throw IOException(
        "$actionLabel failed with result code $resultCode. Check AGRouterManager logs for the response body."
      )
    }
  }

  private fun isSuccessfulUbusCall(response: UbusResponse): Boolean {
    if (response.json?.getAsJsonObject("error") != null) {
      return false
    }

    val resultCode = response.firstResultCode()
    return resultCode == null || resultCode == 0
  }

  private fun probeNonDestructiveFileExecCommands() {
    val probes =
      listOf(
        Triple(listOf("/bin/true", "true"), emptyList<String>(), "true"),
        Triple(listOf("/bin/echo", "echo"), listOf("router-probe"), "echo router-probe"),
        Triple(listOf("/bin/sleep", "sleep"), listOf("1"), "sleep 1"),
        Triple(config.wifiCommandCandidates, listOf("up"), "wifi up"),
      )

    for ((commandCandidates, params, description) in probes) {
      val result =
        executeFileCommandWithCandidates(
          commandCandidates = commandCandidates,
          params = params,
          labelPrefix = "ubus-file-exec-probe-${description.replace(' ', '-')}",
          actionDescription = description,
        )

      Log.d(
        TAG,
        "Non-destructive file.exec probe '$description' finished with command='${result.command}' body=${result.response.bodyText}",
      )
    }
  }

  private data class FileExecResult(
    val command: String,
    val response: UbusResponse,
  )

  private fun executeFileCommandWithCandidates(
    commandCandidates: List<String>,
    params: List<String>,
    labelPrefix: String,
    actionDescription: String,
  ): FileExecResult {
    val distinctCandidates = commandCandidates.distinct()
    var lastResponse: UbusResponse? = null
    var lastCommand: String? = null

    for ((index, commandCandidate) in distinctCandidates.withIndex()) {
      val response =
        callUbus(
          objectName = "file",
          methodName = "exec",
          parameters = mapOf("command" to commandCandidate, "params" to params),
          label = "$labelPrefix-${index + 1}",
        )
      lastResponse = response
      lastCommand = commandCandidate

      val errorObject = response.json?.getAsJsonObject("error")
      if (errorObject != null) {
        Log.d(
          TAG,
          "file.exec candidate '$commandCandidate' for $actionDescription returned JSON-RPC error body=${response.bodyText}",
        )
        continue
      }

      val resultCode = response.firstResultCode()
      if (resultCode == null || resultCode == 0) {
        Log.d(TAG, "file.exec candidate '$commandCandidate' for $actionDescription succeeded")
        return FileExecResult(command = commandCandidate, response = response)
      }

      Log.d(
        TAG,
        "file.exec candidate '$commandCandidate' for $actionDescription returned result code $resultCode body=${response.bodyText}",
      )
    }

    return FileExecResult(
      command = lastCommand ?: distinctCandidates.firstOrNull().orEmpty(),
      response = requireNotNull(lastResponse) { "No file.exec candidates configured for $actionDescription" },
    )
  }

  private fun hasJsonRpcError(response: UbusResponse, code: Int): Boolean {
    return response.json?.getAsJsonObject("error")?.get("code")?.asInt == code
  }

  private fun callUbus(
    objectName: String,
    methodName: String,
    parameters: Map<String, Any?>,
    label: String,
  ): UbusResponse {
    val currentStok = requireNotNull(stok) { "No ubus session token available. Call login() first." }
    val ubusUrl = resolveUrl(config.ubusPath)
    val requestBody =
      gson
        .toJson(
          mapOf(
            "jsonrpc" to "2.0",
            "id" to 1,
            "method" to "call",
            "params" to listOf(currentStok, objectName, methodName, parameters),
          ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

    val requestBuilder =
      Request.Builder()
        .url(ubusUrl)
        .post(requestBody)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json; charset=utf-8")
        .header("Referer", dashboardUrl())
        .header("Origin", config.baseUrl)
        .header("X-Requested-With", "XMLHttpRequest")

    sysauthCookie?.let { cookieValue ->
      requestBuilder.header("Cookie", cookieValue)
    }

    val response = execute(requestBuilder.build())
    val bodyText = response.body.string()
    logResponseDetails(label, response)
    response.close()

    return UbusResponse(
      response = response,
      bodyText = bodyText,
      json = runCatching { JsonParser.parseString(bodyText).asJsonObject }.getOrNull(),
    )
  }

  private fun probeUbusAccess(currentStok: String) {
    val probes =
      listOf(
        Triple("session", "access", mapOf("scope" to "ubus", "object" to config.ubusObject, "function" to config.ubusFlag)),
        Triple("ubus", "list", emptyMap<String, Any?>()),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "network", "function" to "restart")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "uci", "function" to "get")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "uci", "function" to "set")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "uci", "function" to "commit")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "file", "function" to "exec")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "iwinfo", "function" to "devices")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "network.interface", "function" to "down")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "network.interface", "function" to "up")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "network.wireless", "function" to "down")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "network.wireless", "function" to "up")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "luci-rpc", "function" to "getWirelessDevices")),
        Triple("session", "access", mapOf("scope" to "ubus", "object" to "luci-rpc", "function" to "setInitAction")),
      )

    for ((objectName, methodName, parameters) in probes) {
      val response =
        runCatching { callUbus(objectName = objectName, methodName = methodName, parameters = parameters, label = "ubus-probe-$objectName-$methodName") }
          .getOrElse { exception ->
            Log.e(TAG, "Ubus probe failed for $objectName.$methodName: ${exception.message}")
            continue
          }

      Log.d(
        TAG,
        "Ubus probe token=$currentStok object=$objectName method=$methodName params=$parameters body=${response.bodyText}",
      )
    }
  }

  private fun UbusResponse.firstResultCode(): Int? {
    val resultArray = json?.getAsJsonArray("result") ?: return null
    return if (resultArray.size() > 0 && resultArray[0].isJsonPrimitive) {
      resultArray[0].asInt
    } else {
      null
    }
  }

  private fun UbusResponse.secondResultPayload() =
    json?.getAsJsonArray("result")?.let { resultArray ->
      if (resultArray.size() > 1) resultArray[1] else null
    }

  private fun request(
    path: String,
    requestBody: okhttp3.RequestBody,
    includeCsrf: Boolean = true,
  ): Request {
    val targetUrl = if (includeCsrf) authenticatedLuciUrl(path) else resolveUrl(path)
    val requestBuilder =
      Request.Builder()
        .url(targetUrl)
        .post(requestBody)
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", config.baseUrl)
        .header("Referer", "${config.baseUrl}/")
        .header("X-Requested-With", "XMLHttpRequest")

    return requestBuilder.build()
  }

  private fun getRequest(path: String, useAbsoluteUrl: Boolean = false): Request {
    val targetUrl =
      when {
        useAbsoluteUrl -> path
        stok != null && path.startsWith(config.luciBasePath) -> authenticatedLuciUrl(path.removePrefix(config.luciBasePath)).toString()
        else -> resolveUrl(path).toString()
      }
    val requestBuilder =
      Request.Builder()
        .url(targetUrl)
        .get()
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", config.baseUrl)
        .header("Referer", "${config.baseUrl}${config.luciBasePath}")
        .header("X-Requested-With", "XMLHttpRequest")

    return requestBuilder.build()
  }

  private fun resolveUrl(path: String) =
    config.baseUrl.toHttpUrl().newBuilder().encodedPath(path).build()

  private fun authenticatedLuciUrl(path: String): okhttp3.HttpUrl {
    val currentStok = requireNotNull(stok) { "No LuCI stok available. Call login() first." }
    val normalizedPath = when {
      path.isBlank() -> ""
      path.startsWith(config.luciBasePath) -> path.removePrefix(config.luciBasePath)
      path.startsWith("/") -> path
      else -> "/$path"
    }
    val encodedPath = "${config.luciBasePath}/;stok=$currentStok$normalizedPath"
    return config.baseUrl.toHttpUrl().newBuilder().encodedPath(encodedPath).build()
  }

  private fun execute(request: Request): Response {
    val response =
      try {
        client.newCall(request).execute()
      } catch (exception: SocketTimeoutException) {
        throw IOException(
          "Timed out while calling router API ${request.method} ${request.url}. " +
            "Check that the phone is connected to the router Wi-Fi and the router is reachable.",
          exception,
        )
      }
    if (response.code == 403) {
      response.close()
      Log.d(TAG, "Router returned 403 for ${request.method} ${request.url}. Refreshing stok with a fresh login.")
      login()
      val retryRequest = rebuildAuthenticatedRequest(request)
      val retryResponse =
        try {
          client.newCall(retryRequest).execute()
        } catch (exception: SocketTimeoutException) {
          throw IOException(
            "Timed out while retrying router API ${retryRequest.method} ${retryRequest.url} after refreshing stok.",
            exception,
          )
        }
      if (!retryResponse.isSuccessful) {
        val retryBodyText = retryResponse.body.string()
        logFailedResponse("retry", retryRequest, retryResponse.code, retryResponse.message, retryBodyText)
        retryResponse.close()
        throw IOException(
          "Router API retry failed: ${retryRequest.method} ${retryRequest.url} -> ${retryResponse.code} ${retryResponse.message}. Check AGRouterManager logs for the response body."
        )
      }
      return retryResponse
    }
    if (!response.isSuccessful) {
      val bodyText = response.body.string()
      logFailedResponse("request", request, response.code, response.message, bodyText)
      response.close()
      throw IOException(
        "Router API request failed: ${request.method} ${request.url} -> ${response.code} ${response.message}. Check AGRouterManager logs for the response body."
      )
    }
    return response
  }

  private fun executeAllowingRedirects(request: Request): Response {
    return try {
      client.newCall(request).execute()
    } catch (exception: SocketTimeoutException) {
      throw IOException(
        "Timed out while calling router API ${request.method} ${request.url}. " +
          "Check that the phone is connected to the router Wi-Fi and the router is reachable.",
        exception,
      )
    }
  }

  private fun rebuildAuthenticatedRequest(request: Request): Request {
    val builder = request.newBuilder()
    val path = request.url.encodedPath
    if (path.startsWith(config.luciBasePath)) {
      val sanitizedPath =
        path
          .removePrefix(config.luciBasePath)
          .replace(Regex("^/;stok=[A-Za-z0-9_-]+"), "")
      builder.url(authenticatedLuciUrl(sanitizedPath))
    }
    sysauthCookie?.let { builder.header("Cookie", it) }
    return builder.build()
  }

  private fun logFailedResponse(
    label: String,
    request: Request,
    code: Int,
    message: String,
    bodyText: String,
  ) {
    Log.e(TAG, "Router $label failed: ${request.method} ${request.url} -> $code $message")
    Log.e(TAG, "Router $label response body: $bodyText")
  }

  companion object {
    private fun defaultHttpClient(cookieJar: PersistentCookieJar): OkHttpClient =
      OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
  }
}

class PersistentCookieJar : CookieJar {
  private val cookieStore = mutableMapOf<String, List<Cookie>>()

  override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
    cookieStore[url.host] = cookies
  }

  override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
    val cookies = cookieStore[url.host].orEmpty()
    return cookies.filter { it.matches(url) }
  }

  fun findCookieValue(url: okhttp3.HttpUrl, name: String): String? {
    return loadForRequest(url).firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
  }

  fun clear() {
    cookieStore.clear()
  }
}