package com.animetoki

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import org.jsoup.nodes.Document

// Type aliases for JSON handling
typealias JsonObject = Map<String, Any?>
typealias JsonArray = List<JsonObject>

// Extension function for JSON parsing (defined once)
fun String.parseJsonObject(): JsonObject {
    @Suppress("UNCHECKED_CAST")
    return parseJson<Map<String, Any?>>(this) as JsonObject
}

// Helper extension functions
fun JsonObject.getArray(key: String): JsonArray? {
    return this[key] as? JsonArray
}

fun JsonObject.obj(key: String): JsonObject? {
    return this[key] as? JsonObject
}

val JsonObject.string: String?
    get() = this as? String

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
                
                AnimeSearchResponse(
                    title = title,
                    url = link,
                    posterUrl = image,
                    apiName = name
                )
            } else null
        }
    }

    // =============================== LOAD SERIES ===============================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title and poster
        val title = document.select("h1, .entry-title").first()?.text()?.trim() ?: "Unknown"
        val poster = document.select("img.attachment-post-thumbnail, .featured-image img, .post-thumbnail img")
            .first()?.attr("src")?.takeIf { it.startsWith("http") }
        
        // Find the cloud button link
        val cloudButton = document.select("a[href*='cloud.animetoki.com'], .download-now a, a:contains(Download Now)")
            .firstOrNull()?.attr("href") ?: findCloudLinkFromScript(document)
        
        if (cloudButton.isNullOrBlank()) {
            throw Exception("Could not find cloud download link on page")
        }

        // Follow redirect to get the actual cloud page with token
        val cloudResponse = app.get(cloudButton, allowRedirects = true)
        val finalCloudUrl = cloudResponse.url
        
        // Extract the base path and token from the cloud URL
        val (basePath, token) = extractCloudPathAndToken(finalCloudUrl)
        
        // Fetch seasons from API
        val seasons = fetchSeasons(basePath, token)
        
        // Create episodes from seasons
        val episodes = mutableListOf<Episode>()
        for (season in seasons) {
            val seasonEpisodes = fetchEpisodesForSeason(basePath, season.id, token, season.name)
            episodes.addAll(seasonEpisodes)
        }

        return AnimeLoadResponse(
            name = title,
            url = url,
            posterUrl = poster,
            episodes = mutableMapOf(DubStatus.Subbed to episodes.sortedBy { it.episode }),
            apiName = name
        )
    }

    private suspend fun findCloudLinkFromScript(document: Document): String? {
        val scripts = document.select("script")
        for (script in scripts) {
            val html = script.html()
            val pattern = Regex("""https://cloud\.animetoki\.com/[^\s"'<>]+""")
            val match = pattern.find(html)
            if (match != null) {
                return match.value
            }
        }
        return null
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
        } ?: throw Exception("Failed to fetch seasons from API")
        
        val json = response.text.parseJsonObject()
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

    // =============================== FETCH EPISODES FOR SEASON ===============================
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
        
        val json = response.text.parseJsonObject()
        val filesArray = json.getArray("files") ?: return emptyList()
        
        return filesArray.mapNotNull { item ->
            val mimeType = item.obj("mimeType")?.string ?: return@mapNotNull null
            if (mimeType.startsWith("video/")) {
                val fileId = item.obj("id")?.string ?: return@mapNotNull null
                val fileName = item.obj("name")?.string ?: return@mapNotNull null
                val fileSize = item.obj("size")?.string?.toLongOrNull() ?: 0L
                
                // Extract episode number from filename
                val episodeNum = extractEpisodeNumber(fileName)
                
                // Encode filename for URL
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
        
        val numberMatch = Regex("""(\d+)""").find(fileName)
        return numberMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    // =============================== LOAD VIDEO LINKS ===============================
    override suspend fun loadLinks(
        url: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val quality = when {
            url.contains("1080p", ignoreCase = true) -> QUALITY_1080p
            url.contains("720p", ignoreCase = true) -> QUALITY_720p
            url.contains("480p", ignoreCase = true) -> QUALITY_480p
            else -> QUALITY_UNKNOWN
        }
        
        callback.invoke(
            ExtractorLink(
                source = name,
                name = "AnimeToki Cloud",
                url = url,
                referer = mainUrl,
                quality = quality,
                type = if (url.contains(".mkv")) TvType.Episode else TvType.Episode,
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
