package com.animetoki

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.json.JSONObject

class AnimeTokiProvider : MainAPI() {
    override var mainUrl = "https://animetoki.com"
    override var name = "AnimeToki"
    override var lang = "en"
    override val hasMainPage = false
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        const val CLOUD_BASE = "https://cloud.animetoki.com"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("article, .post, .search-result, .entry").mapNotNull { element ->
            val titleElement = element.select("h2 a, h1 a, .entry-title a").first()
            val title = titleElement?.text()?.trim()
            val link = titleElement?.attr("href") ?: element.select("a").first()?.attr("href")
            val image = element.select("img").first()?.attr("src")

            if (!title.isNullOrBlank() && !link.isNullOrBlank() && 
                !title.contains("Server renewal", ignoreCase = true) &&
                !title.contains("Review of", ignoreCase = true)) {
                
                newAnimeSearchResponse(
                    name = title,
                    url = link,
                    posterUrl = image
                )
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.select("h1, .entry-title").first()?.text()?.trim() ?: "Unknown"
        val poster = document.select("img").first()?.attr("src")?.takeIf { it.startsWith("http") }
        
        val cloudButton = document.select("a[href*='cloud.animetoki.com']").firstOrNull()?.attr("href")
            ?: throw Exception("Cloud link not found")
        
        val cloudResponse = app.get(cloudButton, allowRedirects = true)
        val finalUrl = cloudResponse.url
        
        val path = java.net.URI.create(finalUrl).path
        val token = finalUrl.substringAfter("t=")
        
        val seasons = fetchSeasons(path, token)
        
        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            episodes.addAll(fetchEpisodesForSeason(path, season.id, token))
        }

        return newAnimeLoadResponse(
            name = title,
            url = url,
            posterUrl = poster,
            episodes = mapOf(DubStatus.Subbed to episodes.sortedBy { it.episode })
        )
    }

    private suspend fun fetchSeasons(basePath: String, token: String): List<SeasonData> {
        val apiUrl = "$CLOUD_BASE$basePath?t=$token"
        
        val response = safeApiCall {
            app.post(
                url = apiUrl,
                headers = mapOf("Referer" to mainUrl, "X-Requested-With" to "XMLHttpRequest")
            )
        } ?: return emptyList()
        
        val jsonString = response.toString()
        val jsonObject = JSONObject(jsonString)
        val filesArray = jsonObject.getJSONArray("files")
        
        val seasons = mutableListOf<SeasonData>()
        for (i in 0 until filesArray.length()) {
            val file = filesArray.getJSONObject(i)
            if (file.getString("mimeType") == "application/vnd.google-apps.folder") {
                seasons.add(SeasonData(file.getString("id"), file.getString("name")))
            }
        }
        return seasons
    }

    private suspend fun fetchEpisodesForSeason(basePath: String, folderId: String, token: String): List<Episode> {
        val folderApiUrl = "$CLOUD_BASE$basePath/$folderId?t=$token"
        
        val response = safeApiCall {
            app.post(
                url = folderApiUrl,
                headers = mapOf("Referer" to mainUrl, "X-Requested-With" to "XMLHttpRequest")
            )
        } ?: return emptyList()
        
        val jsonString = response.toString()
        val jsonObject = JSONObject(jsonString)
        val filesArray = jsonObject.getJSONArray("files")
        
        val episodes = mutableListOf<Episode>()
        for (i in 0 until filesArray.length()) {
            val file = filesArray.getJSONObject(i)
            val mimeType = file.getString("mimeType")
            if (mimeType.startsWith("video/")) {
                val id = file.getString("id")
                val name = file.getString("name")
                
                val episodeNum = Regex("""(?:Episode|EP|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.get(1)?.toIntOrNull()
                
                val encodedName = Base64.encodeToString(name.toByteArray(), Base64.NO_WRAP)
                val videoUrl = "$CLOUD_BASE/?a=download&id=$id&name=$encodedName&n=2"
                
                newEpisode(
                    url = videoUrl,
                    name = name,
                    episode = episodeNum
                )
            }
        }
        return episodes.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                source = name,
                name = "AnimeToki",
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("Referer" to mainUrl)
            )
        )
        return true
    }

    data class SeasonData(val id: String, val name: String)
}
