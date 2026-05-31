package com.animetoki

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeTokiProvider : MainAPI() {
    override var mainUrl = "https://animetoki.com"
    override var name = "AnimeToki"
    override var lang = "en"  // Changed from 'val' to 'var'
    override val hasMainPage = false
    override val hasQuickSearch = false
    // Removed 'override val isDown = false' - not needed, inherited from MainAPI
    override val supportedTypes = setOf(TvType.Anime)

    // ... rest of the code ...

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.select("h1, .entry-title").first()?.text()?.trim() ?: "Unknown"
        val poster = document.select("img.attachment-post-thumbnail, .featured-image img, .post-thumbnail img")
            .first()?.attr("src")?.takeIf { it.startsWith("http") }
        
        val cloudButton = document.select("a[href*='cloud.animetoki.com'], .download-now a, a:contains(Download Now)")
            .firstOrNull()?.attr("href") ?: findCloudLinkFromScript(document)
        
        if (cloudButton.isNullOrBlank()) {
            throw Exception("Could not find cloud download link on page")  // Changed to Exception
        }

        val cloudResponse = app.get(cloudButton, allowRedirects = true)
        val finalCloudUrl = cloudResponse.url
        
        val (basePath, token) = extractCloudPathAndToken(finalCloudUrl)
        val seasons = fetchSeasons(basePath, token)
        
        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            val seasonEpisodes = fetchEpisodesForSeason(basePath, season.id, token, season.name)
            episodes.addAll(seasonEpisodes)
        }
        
        return AnimeLoadResponse(
            name = title,
            url = url,
            apiName = name,
            type = TvType.Anime,
            posterUrl = poster,
            episodes = mutableMapOf(DubStatus.Subbed to episodes.sortedBy { it.episode })  // Changed to mutableMapOf
        )
    }

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
        } ?: throw Exception("Failed to fetch seasons from API")  // Changed to Exception
        
        val json = response.text.parseJsonObject()  // .text is correct here (it's a String property)
        val filesArray = json.getArray("files") ?: return emptyList()
        
        return filesArray.mapNotNull { item ->
            val mimeType = item.obj("mimeType")?.string ?: return@mapNotNull null
            if (mimeType == "application/vnd.google-apps.folder") {
                val id = item.obj("id")?.string ?: return@mapNotNull null
                val name = item.obj("name")?.string ?: return@mapNotNull null
                SeasonData(id, name)
            } else null
        }
    }

    private suspend fun fetchEpisodesForSeason(basePath: String, folderId: String, token: String, seasonName: String): List<Episode> {
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
        
        val json = response.text.parseJsonObject()  // .text is correct here
        val filesArray = json.getArray("files") ?: return emptyList()
        
        return filesArray.mapNotNull { item ->
            val mimeType = item.obj("mimeType")?.string ?: return@mapNotNull null
            if (mimeType.startsWith("video/")) {
                val fileId = item.obj("id")?.string ?: return@mapNotNull null
                val fileName = item.obj("name")?.string ?: return@mapNotNull null
                val fileSize = item.obj("size")?.string?.toLongOrNull() ?: 0L
                
                val episodeNum = extractEpisodeNumber(fileName)
                val encodedName = Base64.encodeToString(fileName.toByteArray(), Base64.NO_WRAP)
                val videoUrl = "$CLOUD_BASE/?a=download&id=$fileId&name=$encodedName&n=2"
                
                Episode(
                    link = videoUrl,
                    name = fileName,
                    episode = episodeNum,
                    posterUrl = null,
                    size = fileSize
                )
            } else null
        }.sortedBy { it.episode }
    }

    // ... rest of the helper functions remain the same ...
}

fun String.parseJsonObject(): JsonObject {
    return parseJson<Map<String, Any?>>(this) as Map<String, Any?>  // Fixed parseJson call
}
