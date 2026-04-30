/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.AgentAction
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.CallJsSkillResult
import com.google.ai.edge.gallery.common.CallJsSkillResultImage
import com.google.ai.edge.gallery.common.CallJsSkillResultWebview
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.infrastructure.ROUTER_PASSWORD_SECRET_KEY
import com.google.ai.edge.gallery.infrastructure.ROUTER_USERNAME_SECRET_KEY
import com.google.ai.edge.gallery.infrastructure.RouterApiConfig
import com.google.ai.edge.gallery.infrastructure.RouterManager
import com.google.ai.edge.gallery.tools.TenderScraper
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking

private const val TAG = "AGAgentTools"

class AgentTools() : ToolSet {
  lateinit var context: Context
  lateinit var skillManagerViewModel: SkillManagerViewModel

  private val _actionChannel = Channel<AgentAction>(Channel.UNLIMITED)
  val actionChannel: ReceiveChannel<AgentAction> = _actionChannel
  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

  /** Loads skill. */
  @Tool(description = "Loads a skill.")
  fun loadSkill(
    @ToolParam(description = "The name of the skill to load.") skillName: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }
      val skillContent =
        if (skill != null) {
          "---\nname: ${skill.name}\ndescription: ${skill.description}\n---\n\n${skill.instructions}"
        } else {
          "Skill not found"
        }
      Log.d(TAG, "load skill. Skill content:\n$skillContent")
      if (skill != null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Loading skill \"$skillName\"",
            inProgress = true,
            addItemTitle = "Load \"${skill.name}\"",
            addItemDescription = "Description: ${skill.description}",
            customData = skill,
          )
        )
      } else {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to load skill \"$skillName\"",
            inProgress = false,
          )
        )
      }

      mapOf("skill_name" to skillName, "skill_instructions" to skillContent)
    }
  }

  /** Call JS skill */
  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      Log.d(
        TAG,
        "runJS tool called with:" +
          "\n- skillName: ${skillName}\n- scriptName: ${scriptName}\n- data: ${data}\n",
      )

      val skills = skillManagerViewModel.getSelectedSkills()
      val skill = skills.find { it.name == skillName.trim() }

      if (skill == null) {
        _actionChannel.send(
          SkillProgressAgentAction(
            label = "Failed to call skill \"$scriptName\"",
            inProgress = false,
          )
        )
        return@runBlocking mapOf(
          "error" to "Skill \"${scriptName}\" not found",
          "status" to "failed",
        )
      }

      // Check secret. If a skill requires a secret and the secret is not provided, show error.
      var secret = ""
      if (skill.requireSecret) {
        val savedSecret =
          skillManagerViewModel.dataStoreRepository.readSecret(
            key = getSkillSecretKey(skillName = skillName)
          )
        if (savedSecret == null || savedSecret.isEmpty()) {
          val action =
            AskInfoAgentAction(
              dialogTitle = "Enter secret",
              fieldLabel =
                skill.requireSecretDescription.ifEmpty {
                  "The JS script needs a secret (API key / token) to proceed:"
                },
            )
          _actionChannel.send(action)
          secret = action.result.await()
          if (secret.isNotEmpty()) {
            skillManagerViewModel.dataStoreRepository.saveSecret(
              key = getSkillSecretKey(skillName = skillName),
              value = secret,
            )
            Log.d(TAG, "Got Secret from ask info dialog: ${secret.substring(0, 3)}")
          } else {
            Log.d(TAG, "The ask info dialog got cancelled. No secret.")
          }
        } else {
          secret = savedSecret
        }
      }

      // Get the url for the skill.
      val url =
        skillManagerViewModel.getJsSkillUrl(skillName = skillName, scriptName = scriptName)
          ?: return@runBlocking mapOf(
            "result" to "JS Skill URL not set properly or skill not found"
          )
      Log.d(TAG, "Calling JS script.\n- url: $url\n- data: $data")

      // Update progress.
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Calling JS script \"${skillName}/${scriptName}\"",
          inProgress = true,
          addItemTitle = "Call JS script: \"${skillName}/${scriptName}\"",
          addItemDescription = "- URL: ${url.replace(LOCAL_URL_BASE, "")}\n- Data: $data",
          customData = skill,
        )
      )

      // Actually run it and wait for the result.
      val action =
        CallJsAgentAction(url = url, data = data.trim().ifEmpty { "{}" }, secret = secret)
      _actionChannel.send(action)
      val result = action.result.await()

      // Try to parse result to CallJsSkillResult.
      val moshi: Moshi = Moshi.Builder().build()
      val jsonAdapter: JsonAdapter<CallJsSkillResult> =
        moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
      val resultJson = runCatching { jsonAdapter.fromJson(result) }.getOrNull()
      val error = resultJson?.error

      // Failed to parse. Treat its whole as a result string.
      if (
        resultJson == null ||
          (resultJson.result == null && resultJson.webview == null && resultJson.image == null)
      ) {
        mapOf("result" to result, "status" to "succeeded")
      }
      // Error case.
      else if (error != null) {
        mapOf("error" to error, "status" to "failed")
      }
      // Non-error cases.
      else {
        // Handle image and webview in result.
        val image = resultJson.image
        val webview = resultJson.webview
        if (image != null) {
          Log.d(TAG, "Got an image response.")
          resultImageToShow = image
        }
        if (webview != null) {
          Log.d(TAG, "Got an webview response.")
          val webviewUrl =
            skillManagerViewModel.getJsSkillWebviewUrl(
              skillName = skillName,
              url = webview.url ?: "",
            )
          Log.d(TAG, "Webview url: $webviewUrl")
          resultWebviewToShow = webview.copy(url = webviewUrl)
        }
        Log.d(TAG, "Result: ${resultJson.result}")
        mapOf("result" to (resultJson.result ?: ""), "status" to "succeeded")
      }
    }
  }

  @Tool(
    description =
      "Run an Android intent. It is used to interact with the app to perform certain actions."
  )
  fun runIntent(
    @ToolParam(description = "The intent to run.") intent: String,
    @ToolParam(
      description = "A JSON string containing the parameter values required for the intent."
    )
    parameters: String,
  ): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "Run intent. Intent: '$intent', parameters: '$parameters'")
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Executing intent \"$intent\"",
          inProgress = true,
          addItemTitle = "Execute intent \"$intent\"",
          addItemDescription = "Parameters: $parameters",
        )
      )
      if (IntentHandler.handleAction(context, intent, parameters)) {
        return@runBlocking mapOf(
          "action" to intent,
          "parameters" to parameters,
          "result" to "succeeded",
        )
      } else {
        return@runBlocking mapOf(
          "action" to intent,
          "parameters" to parameters,
          "result" to "failed",
        )
      }
    }
  }

  @Tool(
    description =
      "Native Skill: scrape the latest South African tender listings from eTenders and return tender metadata plus document links. Use this when the user asks for new tenders, procurement notices, or tender documents."
  )
  fun scrapeNewTenders(): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "Running Native Skill scrapeNewTenders")
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Running Native Skill: Scrape new tenders",
          inProgress = true,
          addItemTitle = "Native Skill: scrapeNewTenders",
          addItemDescription = "Scrape eTenders listings and download tender PDFs to app-scoped storage.",
        )
      )

      val scraper = TenderScraper(context = context)
      val tenders = scraper.getLatestTenders(maxPages = 1, downloadDocuments = true)
      val result =
        if (tenders.isEmpty()) {
          "No tender listings were found on the latest eTenders page."
        } else {
          tenders.joinToString(separator = "\n\n") { tender ->
            buildString {
              append("Title: ${tender.title}")
              tender.category?.takeIf { it.isNotBlank() }?.let { append("\nCategory: $it") }
              tender.advertisedDate?.takeIf { it.isNotBlank() }?.let {
                append("\nAdvertised: $it")
              }
              tender.closingDate?.takeIf { it.isNotBlank() }?.let { append("\nClosing: $it") }
              tender.detailUrl?.takeIf { it.isNotBlank() }?.let { append("\nDetails: $it") }
              tender.documentUrl?.takeIf { it.isNotBlank() }?.let { append("\nDocument: $it") }
              tender.localPath?.takeIf { it.isNotBlank() }?.let {
                append("\nSaved to: $it")
              }
            }
          }
        }

      mapOf("result" to result, "status" to "succeeded", "skill_type" to "native")
    }
  }

  @Tool(
    description =
      "Native Skill: stabilize internet connectivity by blinking the local router connection. Use this when the user or AI detects a network failure or unstable connection and wants to rotate the mobile connection."
  )
  fun fixInternet(): Map<String, String> {
    return runBlocking(Dispatchers.Default) {
      Log.d(TAG, "Running Native Skill fixInternet")
      _actionChannel.send(
        SkillProgressAgentAction(
          label = "Running Native Skill: Fix internet stability",
          inProgress = true,
          addItemTitle = "Native Skill: fixInternet",
          addItemDescription = "Blink the router mobile connection to recover from network failure.",
        )
      )

      val password =
        getOrAskSecret(
          secretKey = ROUTER_PASSWORD_SECRET_KEY,
          dialogTitle = "Router password",
          fieldLabel = "Enter the Rain router password for the Native Skill.",
        )

      Log.d(TAG, "A.M.E. is resetting the Rain 101 Pro session to stabilize the forge")
      val routerManager =
        RouterManager(
          config =
            RouterApiConfig(
              baseUrl = "http://192.168.0.1",
              password = password,
            )
        )
      routerManager.blinkConnection()

      mapOf(
        "result" to "The Sovereign Signal has been stabilized",
        "status" to "succeeded",
        "skill_type" to "native",
      )
    }
  }

  @Tool(
    description =
      "Native Skill: stabilize internet connectivity by blinking the local router connection. Use this when the user or AI detects a network failure or unstable connection and wants to rotate the mobile connection."
  )
  fun fixInternetStability(): Map<String, String> {
    return fixInternet()
  }

  fun sendAgentAction(action: AgentAction) {
    runBlocking(Dispatchers.Default) { _actionChannel.send(action) }
  }

  private suspend fun getOrAskSecret(
    secretKey: String,
    dialogTitle: String,
    fieldLabel: String,
  ): String {
    val existing = skillManagerViewModel.dataStoreRepository.readSecret(secretKey)
    if (!existing.isNullOrBlank()) {
      return existing.trim()
    }

    val action = AskInfoAgentAction(dialogTitle = dialogTitle, fieldLabel = fieldLabel)
    _actionChannel.send(action)
    val value = action.result.await().trim()
    require(value.isNotBlank()) { "$dialogTitle is required to run this Native Skill." }
    skillManagerViewModel.dataStoreRepository.saveSecret(secretKey, value)
    return value
  }
}

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}
