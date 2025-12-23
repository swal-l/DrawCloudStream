package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class MovixClubProvider : MainAPI() {
    override var mainUrl = "https://movix.club"
    override var name = "Movix"
    private val apiUrl = "https://api.movix.club"
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "2025" to "📅 Sorties 2025",
        "2024" to "📅 Sorties 2024",
        "series" to "📺 Séries Populaires",
        "action" to "💥 Films d'Action",
        "aventure" to "🌍 Aventure",
        "anime" to "🗾 Animes",
        "drame" to "🎭 Drame"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val query = request.data
        val searchResults = search(query)
        return newHomePageResponse(request.name, searchResults)
    }

    // JSON Data Classes
    data class MovixSearchResponse(
        val results: List<SearchResultItem>?
    )

    data class SearchResultItem(
        val id: Int,
        val name: String,
        val poster: String?,
        val type: String?, // "animes", "movie", "ebook"
        @JsonProperty("model_type") val modelType: String?,
        val year: Int?,
        @JsonProperty("tmdb_id") val tmdbId: Int?,
        val description: String?,
        val backdrop: String?,
        val rating: Float?
    )

    data class SeasonResponse(
        val success: Boolean,
        val pagination: SeasonPagination?
    )

    data class SeasonPagination(
        val data: List<SeasonItem>?
    )

    data class SeasonItem(
        val id: Int,
        val number: Int,
        @JsonProperty("episodes_count") val episodesCount: Int?
    )

    data class EpisodeResponse(
        val success: Boolean,
        val pagination: EpisodePagination?
    )

    data class EpisodePagination(
        val data: List<EpisodeItem>?
    )

    data class EpisodeItem(
        val id: Int,
        val name: String?,
        val poster: String?,
        @JsonProperty("episode_number") val episodeNumber: Int,
        @JsonProperty("season_number") val seasonNumber: Int,
        val description: String?,
        @JsonProperty("primary_video") val primaryVideo: PrimaryVideo?
    )

    data class PrimaryVideo(
        val lien: String?
    )
    
    // Movie Download Response (Partial)
    data class MovieDownloadResponse(
        val success: Boolean,
        val all: List<MovieDownloadItem>?
    )
    
    data class MovieDownloadItem(
        val id: Int,
        @JsonProperty("host_name") val hostName: String?,
        val quality: String?
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/api/search?title=$query"
        val headers = mapOf("User-Agent" to userAgent)
        val response = app.get(url, headers = headers).parsedSafe<MovixSearchResponse>()
        
        val validResults = response?.results?.mapNotNull { item ->
            if (item.type == "ebook") return@mapNotNull null
            
            val typeStr = item.type ?: item.modelType
            
            val type = when(typeStr) {
                "movie" -> TvType.Movie
                "animes" -> TvType.Anime
                else -> TvType.TvSeries
            }
            
            newTvSeriesSearchResponse(item.name, item.id.toString(), type) {
                this.posterUrl = item.poster
                this.year = item.year
                this.posterHeaders = mapOf("User-Agent" to userAgent)
                
                // Use backdrop if available, otherwise fallback to poster
                if (!item.backdrop.isNullOrBlank()) {
                     this.posterUrl = item.poster
                }
            }
        } ?: emptyList()

        return validResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url
        val headers = mapOf("User-Agent" to userAgent)
        
        // Try fetching seasons
        val seasonUrl = "$apiUrl/api/darkiworld/seasons/$id"
        val seasonResponse = app.get(seasonUrl, headers = headers).parsedSafe<SeasonResponse>()

        if (seasonResponse?.success == true && !seasonResponse.pagination?.data.isNullOrEmpty()) {
            // It's a TV Series
            val seasons = seasonResponse.pagination.data.sortedBy { it.number }
            val episodes = ArrayList<Episode>()
            
            seasons.forEach { season ->
                val epUrl = "$apiUrl/api/darkiworld/episodes/$id/${season.number}"
                val epResponse = app.get(epUrl, headers = headers).parsedSafe<EpisodeResponse>()
                
                epResponse?.pagination?.data?.forEach { ep ->
                    episodes.add(
                        newEpisode(ep.primaryVideo?.lien ?: "") {
                            this.name = ep.name
                            this.season = ep.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.poster
                            this.description = ep.description
                        }
                    )
                }
            }
            
            return newTvSeriesLoadResponse("TV Series $id", id, TvType.TvSeries, episodes)
        } else {
             // Treat as Movie (Legacy/Fallback)
             // We use the ID as the data
             return newMovieLoadResponse("Movie $id", id, TvType.Movie, id)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to userAgent)

        // If data is a URL (from TV episode), use it
        if (data.startsWith("http")) {
             loadExtractor(data, subtitleCallback, callback)
             return true
        }
        
        // Movie: data is the Movie ID (String)
        val movieId = data
        val url = "$apiUrl/api/darkiworld/download/movie/$movieId"
        
        try {
            val res = app.get(url, headers = headers).parsedSafe<MovieDownloadResponse>()
            
            res?.all?.forEach { item ->
                // For each download item, we need to decode it to get the link
                val decodeUrl = "$apiUrl/api/darkiworld/decode/${item.id}"
                val decodeRes = app.get(decodeUrl, headers = headers).parsedSafe<DecodeResponse>()
                
                val link = decodeRes?.embedUrl?.lien
                if (!link.isNullOrBlank()) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    data class DecodeResponse(
        val success: Boolean,
        @JsonProperty("embed_url") val embedUrl: EmbedUrl?
    )

    data class EmbedUrl(
        val lien: String?
    )
}
