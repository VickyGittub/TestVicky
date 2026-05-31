package com.animetoki

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Document
import com.fasterxml.jackson.databind.ObjectMapper

private val mapper = ObjectMapper()

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

    // =============================== SEARCH ===============================
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
                
                // Using non-deprecated method
                newAnimeSearchResponse(
                    title = title,
                    url = link,
                    posterUrl = image
                )
            } else null
        }
    }

    // =============================== LOAD SERIES ===============================
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

        // Using non-deprecated method
        return newAnimeLoadResponse(
            title = title,
            url = url,
            posterUrl = poster,
            episodes = mapOf(DubStatus.Subbed to episodes.sortedBy { it.episode })
        )
    }

    private fun extractCloudPathAndToken(url: String): Pair<String, String> {
        val uri = java.net.URI.create(url)
        val path = uri.path
        val token = uri.query?.substringAfter("t=") ?: ""
        return Pair(path, token)
    }

    // =============================== FETCH SEASONS ===============================
    private suspend fun fetchSeasons(basePath: String, token: String): List<SeasonData> {
        val apiUrl = "$CLOUD_BASE$basePath?t=$token"
        
        val response = safeApiCall {
            app.post(
                url = apiUrl,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        } ?: return emptyList()
        
        // Fix: Get response body correctly
        val jsonString = try {
            (response as? com.lagradost.nicehttp.Response)?.text ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        
        val json = mapper.readValue(jsonString, Map::class.java) as Map<String, Any>
        val files = json["files"] as? List<Map<String, Any>> ?: return emptyList()
        
        return files.mapNotNull { file ->
            val mimeType = file["mimeType"] as? String
            if (mimeType == "application/vnd.google-apps.folder") {
                val id = file["id"] as? String ?: return@mapNotNull null
                val name = file["name"] as? String ?: return@mapNotNull null
                SeasonData(id, name)
            } else null
        }
    }

    // =============================== FETCH EPISODES FOR SEASON ===============================
    private suspend fun fetchEpisodesForSeason(basePath: String, folderId: String, token: String): List<Episode> {
        val folderApiUrl = "$CLOUD_BASE$basePath/$folderId?t=$token"
        
        val response = safeApiCall {
            app.post(
                url = folderApiUrl,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        } ?: return emptyList()
        
        // Fix: Get response body correctly
        val jsonString = try {
            (response as? com.lagradost.nicehttp.Response)?.text ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        
        val json = mapper.readValue(jsonString, Map::class.java) as Map<String, Any>
        val files = json["files"] as? List<Map<String, Any>> ?: return emptyList()
        
        return files.mapNotNull { file ->
            val mimeType = file["mimeType"] as? String
            if (mimeType?.startsWith("video/") == true) {
                val id = file["id"] as? String ?: return@mapNotNull null
                val name = file["name"] as? String ?: return@mapNotNull null
                
                val episodeNum = extractEpisodeNumber(name)
                val encodedName = Base64.encodeToString(name.toByteArray(), Base64.NO_WRAP)
                val videoUrl = "$CLOUD_BASE/?a=download&id=$id&name=$encodedName&n=2"
                
                // Using non-deprecated method
                newEpisode(
                    link = videoUrl,
                    name = name,
                    episode = episodeNum
                )
            } else null
        }.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(fileName: String): Int? {
        val patterns = listOf(
            Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""- (\d+) -"""),
            Regex("""\[(\d+)\]""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(fileName)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        
        return null
    }

    // =============================== LOAD VIDEO LINKS ===============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            ExtractorLink(
                source = name,
                name = "AnimeToki Cloud",
                url = data,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
        )
        return true
    }

    // =============================== DATA CLASS ===============================
    data class SeasonData(
        val id: String,
        val name: String
    )
}
