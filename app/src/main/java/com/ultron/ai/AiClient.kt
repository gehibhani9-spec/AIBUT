package com.ultron.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Streaming client for Claude, OpenAI-compatible APIs (OpenAI, OpenRouter, Groq...) and Gemini. */
object AiClient {

    fun stream(system: String, msgs: List<Pair<String, String>>, maxTokens: Int, onDelta: (String) -> Unit): String {
        val out = StringBuilder()
        val emit: (String) -> Unit = { if (it.isNotEmpty()) { out.append(it); onDelta(it) } }
        when (Prefs.get("provider", "claude").trim().lowercase()) {
            "openai" -> openai(system, msgs, maxTokens, emit)
            "gemini" -> gemini(system, msgs, maxTokens, emit)
            else -> claude(system, msgs, maxTokens, emit)
        }
        return out.toString()
    }

    private fun key(k: String): String {
        val v = Prefs.get(k).trim()
        if (v.isEmpty()) throw RuntimeException("No API key set. Open settings and add one.")
        return v
    }

    private fun post(url: String, headers: Map<String, String>, body: String, onData: (String) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
            throw RuntimeException("API error $code: $err")
        }
        c.inputStream.bufferedReader().forEachLine { l ->
            if (l.startsWith("data:")) {
                val d = l.substring(5).trim()
                if (d.isNotEmpty() && d != "[DONE]") onData(d)
            }
        }
    }

    private fun claude(system: String, msgs: List<Pair<String, String>>, max: Int, emit: (String) -> Unit) {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
        val body = JSONObject()
            .put("model", Prefs.get("model_claude", "claude-sonnet-5-5"))
            .put("max_tokens", max).put("stream", true)
            .put("system", system).put("messages", arr)
        post("https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key("key_claude"), "anthropic-version" to "2023-06-01"), body.toString()) { d ->
            val j = JSONObject(d)
            if (j.optString("type") == "content_block_delta") {
                val delta = j.optJSONObject("delta")
                if (delta != null && delta.has("text")) emit(delta.getString("text"))
            }
        }
    }

    private fun openai(system: String, msgs: List<Pair<String, String>>, max: Int, emit: (String) -> Unit) {
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
        val body = JSONObject()
            .put("model", Prefs.get("model_openai", "gpt-4o-mini"))
            .put("messages", arr).put("stream", true).put("max_tokens", max)
        val url = Prefs.get("openai_base", "https://api.openai.com/v1/chat/completions")
        post(url, mapOf("Authorization" to "Bearer " + key("key_openai")), body.toString()) { d ->
            val ch = JSONObject(d).optJSONArray("choices")
            if (ch != null && ch.length() > 0) {
                val delta = ch.getJSONObject(0).optJSONObject("delta")
                if (delta != null && !delta.isNull("content") && delta.has("content")) emit(delta.getString("content"))
            }
        }
    }

    private fun gemini(system: String, msgs: List<Pair<String, String>>, max: Int, emit: (String) -> Unit) {
        val contents = JSONArray()
        msgs.forEach {
            contents.put(JSONObject()
                .put("role", if (it.first == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", it.second))))
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", max))
        val model = Prefs.get("model_gemini", "gemini-2.0-flash")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" + model +
            ":streamGenerateContent?alt=sse&key=" + key("key_gemini")
        post(url, emptyMap(), body.toString()) { d ->
            val cands = JSONObject(d).optJSONArray("candidates")
            val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) emit(parts.getJSONObject(i).optString("text", ""))
        }
    }
}
