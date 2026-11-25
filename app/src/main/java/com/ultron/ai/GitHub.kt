package com.ultron.ai

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHub {

    const val ANDROID_WORKFLOW = """name: Build APK
on:
  push:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.7
      - run: gradle assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: app/build/outputs/apk/debug/*.apk
"""

    private fun call(method: String, path: String, body: String?): Pair<Int, String> {
        val c = URL("https://api.github.com$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer " + Prefs.get("gh_token").trim())
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to text
    }

    private fun ensureRepo(repo: String) {
        val (code, _) = call("GET", "/repos/$repo", null)
        if (code == 404) {
            val name = repo.substringAfter('/')
            val (c2, t2) = call("POST", "/user/repos",
                JSONObject().put("name", name).put("auto_init", true).put("private", false).toString())
            if (c2 !in 200..299) throw RuntimeException("Could not create repo: $c2 ${t2.take(200)}")
            Thread.sleep(1500)
        } else if (code == 401 || code == 403) {
            throw RuntimeException("GitHub token rejected ($code). Check it in settings.")
        }
    }

    private fun putFile(repo: String, path: String, content: String, msg: String) {
        val (gc, gt) = call("GET", "/repos/$repo/contents/$path", null)
        val body = JSONObject().put("message", msg)
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
        if (gc == 200) body.put("sha", JSONObject(gt).getString("sha"))
        val (pc, pt) = call("PUT", "/repos/$repo/contents/$path", body.toString())
        if (pc !in 200..299) throw RuntimeException("Push failed for $path: $pc ${pt.take(200)}")
    }

    fun push(repoIn: String, files: List<Pair<String, String>>, msg: String, pages: Boolean, androidWf: Boolean): String {
        if (Prefs.get("gh_token").isBlank()) return "No GitHub token set in settings."
        val repo = if (repoIn.contains('/')) repoIn else Prefs.get("gh_user", "gehibhani9-spec") + "/" + repoIn
        ensureRepo(repo)
        val all = ArrayList(files)
        if (androidWf) all.add(".github/workflows/build.yml" to ANDROID_WORKFLOW)
        for ((p, c) in all) putFile(repo, p.trimStart('/'), c, msg)
        var extra = ""
        if (pages) {
            call("POST", "/repos/$repo/pages",
                JSONObject().put("source", JSONObject().put("branch", "main").put("path", "/")).toString())
            extra = " Site: https://" + repo.substringBefore('/') + ".github.io/" + repo.substringAfter('/') + "/"
        }
        if (androidWf) extra += " The APK builds in GitHub Actions; download it from the Actions tab."
        return "Pushed ${all.size} files to $repo.$extra"
    }
}
