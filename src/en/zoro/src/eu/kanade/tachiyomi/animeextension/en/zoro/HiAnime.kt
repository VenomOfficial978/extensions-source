package eu.kanade.tachiyomi.animeextension.en.zoro

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request


private const val OPTIONS = "OPTIONS"

class HiAnime : MainAPI() {
    override var mainUrl = "https://hianime.to"
    override var name = "HiAnime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    val epRegex = Regex("Ep (\\d+)/")
    var sid: HashMap<Int, String?> = hashMapOf() // Url hashcode to sid

    private fun Element.toSearchResult(): SearchResponse {
        val href = fixUrl(this.select("a").attr("href"))
        val title = this.select("h3.film-name").text()
        val subCount =
                this.selectFirst(".film-poster > .tick.ltr > .tick-sub")?.text()?.toIntOrNull()
        val dubCount =
                this.selectFirst(".film-poster > .tick.ltr > .tick-dub")?.text()?.toIntOrNull()

        val posterUrl = fixUrl(this.select("img").attr("data-src"))
        val type = getType(this.selectFirst("div.fd-infor > span.fdi-item")?.text() ?: "")

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(dubCount != null, subCount != null, dubCount, subCount)
        }
    }

    private fun Element.getActorData(): ActorData? {
        var actor: Actor? = null
        var role: ActorRole? = null
        var voiceActor: Actor? = null
        val elements = this.select(".per-info")
        elements.forEachIndexed { index, actorInfo ->
            val name = actorInfo.selectFirst(".pi-name")?.text() ?: return null
            val image = actorInfo.selectFirst("a > img")?.attr("data-src") ?: return null
            when (index) {
                0 -> {
                    actor = Actor(name, image)
                    val castType = actorInfo.selectFirst(".pi-cast")?.text() ?: "Main"
                    role = ActorRole.valueOf(castType)
                }
                1 -> voiceActor = Actor(name, image)
                else -> {}
            }
        }
        return ActorData(actor ?: return null, role, voiceActor = voiceActor)
    }

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie")) TvType.AnimeMovie else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage =
            mainPageOf(
                    "$mainUrl/recently-updated?page=" to "Latest Episodes",
                    "$mainUrl/filter?status=2&language=1&sort=recently_updated&page=" to "Recently Updated (SUB)",
                    "$mainUrl/filter?status=2&language=2&sort=recently_updated&page=" to "Recently Updated (DUB)",
                    "$mainUrl/recently-added?page=" to "New On HiAnime",
                    "$mainUrl/top-airing?page=" to "Top Airing",
                    "$mainUrl/most-popular?page=" to "Most Popular",
                    "$mainUrl/most-favorite?page=" to "Most Favorite",
                    "$mainUrl/completed?page=" to "Latest Completed",
            )

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/search?keyword=$query"
        val res = app.get(link).document

        return res.select("div.flw-item").map { it.toSearchResult() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("${request.data}$page").document
        val items = res.select("div.flw-item").map { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val syncData = tryParseJson<ZoroSyncData>(document.selectFirst("#syncData")?.data())

        val title = document.selectFirst(".anisc-detail > .film-name")?.text().toString()
        val poster = document.selectFirst(".anisc-poster img")?.attr("src")
        val animeId = URI(url).path.split("-").last()

        val subCount = document.selectFirst(".anisc-detail .tick-sub")?.text()?.toIntOrNull()
        val dubCount = document.selectFirst(".anisc-detail .tick-dub")?.text()?.toIntOrNull()

        var dubEpisodes = emptyList<Episode>()
        var subEpisodes = emptyList<Episode>()
        val epRes =
                app.get("$mainUrl/ajax/v2/episode/list/$animeId")
                        .parsedSafe<Response>()
                        ?.getDocument()
        epRes?.select(".ss-list > a[href].ssl-item.ep-item")?.forEachIndexed { index, ep ->
            subCount?.let {
                if (index < it) {
                    subEpisodes +=
                            newEpisode("sub|" + ep.attr("href")) {
                                name = ep.attr("title")
                                episode = ep.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                            }
                }
            }
            dubCount?.let {
                if (index < it) {
                    dubEpisodes +=
                            newEpisode("dub|" + ep.attr("href")) {
                                name = ep.attr("title")
                                episode = ep.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                            }
                }
            }
        }

        val actors =
                document.select("div.block-actors-content div.bac-item").mapNotNull {
                    it.getActorData()
                }

        val recommendations =
                document.select("div.block_area_category div.flw-item").map { it.toSearchResult() }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, subEpisodes)
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            this.recommendations = recommendations
            this.actors = actors
            addMalId(syncData?.malId?.toIntOrNull())
            addAniListId(syncData?.aniListId?.toIntOrNull())

            // adding info
            document.select(".anisc-info > .item").forEach { info ->
                val infoType = info.select("span.item-head").text().removeSuffix(":")
                when (infoType) {
                    "Overview" -> plot = info.selectFirst(".text")?.text()
                    "Japanese" -> japName = info.selectFirst(".name")?.text()
                    "Premiered" ->
                            year =
                                    info.selectFirst(".name")
                                            ?.text()
                                            ?.substringAfter(" ")
                                            ?.toIntOrNull()
                    "Duration" ->
                            duration = getDurationFromString(info.selectFirst(".name")?.text())
                    "Status" -> showStatus = getStatus(info.selectFirst(".name")?.text().toString())
                    "Genres" -> tags = info.select("a").map { it.text() }
                    "MAL Score" -> rating = info.selectFirst(".name")?.text().toRatingInt()
                    else -> {}
                }
            }
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dubType = data.replace("$mainUrl/", "").split("|").first()
        val epId = data.split("|").last().split("=").last()

        val servers: List<String> =
                app.get("$mainUrl/ajax/v2/episode/servers?episodeId=$epId")
                        .parsed<Response>()
                        .getDocument()
                        .select(".server-item[data-type=$dubType][data-id]")
                        .map { it.attr("data-id") }

        // val extractorData = "https://ws1.rapid-cloud.ru/socket.io/?EIO=4&transport=polling"

        // Prevent duplicates
        servers.distinct().apmap { it ->
            val link = "$mainUrl/ajax/v2/episode/sources?id=$it"

            //Workaround
            val animeEpisodeId=data.substringAfterLast("/").substringBeforeLast("-")
            val serverlist = listOf("hd-1", "hd-2")
            for (server in serverlist )
            {
                val api="${BuildConfig.HIANIMEAPI}/api/v2/hianime/episode/sources?animeEpisodeId=$animeEpisodeId?ep=$epId$&server=$server&category=$dubType"
                app.get(api).parsedSafe<Hianime>()?.data?.let { data ->
                    val m3u8Urls = data.sources.map { it.url }
                    val m3u8 = m3u8Urls.firstOrNull()
                    if (m3u8Urls.isNotEmpty()) {
                        M3u8Helper.generateM3u8(
                            "HiAnime ${server.uppercase()} ${dubType.uppercase()}",
                            m3u8 ?:"",
                            ""
                        ).forEach(callback)
                    } else {
                        Log.d("Error:","Not Found")
                    }

                    data.tracks.map { track ->
                        val vtt = track.file
                        val lang=track.label
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang?:"",
                                vtt
                            )
                        )
                    }
                }
            }
            //

            val extractorLink = app.get(link).parsed<RapidCloudResponse>().link
            val hasLoadedExtractorLink =
                    loadExtractor(
                            extractorLink,
                            "https://rapid-cloud.ru/",
                            subtitleCallback,
                            callback
                    )
            if (!hasLoadedExtractorLink) {
                extractRabbitStream(
                        extractorLink,
                        subtitleCallback,
                        // Blacklist VidCloud for now
                        { videoLink ->
                            if (!videoLink.url.contains("betterstream")) callback(videoLink)
                        },
                        false,
                        null,
                        decryptKey = getKey()
                ) { sourceName -> sourceName }
            }
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        // Needs to be object instead of lambda to make it compile correctly
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                if (request.url.toString().endsWith(".ts") &&
                                request.method != OPTIONS
                                // No option requests on VidCloud
                                &&
                                !request.url.toString().contains("betterstream")
                ) {
                    val newRequest =
                            chain.request()
                                    .newBuilder()
                                    .apply {
                                        sid[extractorLink.url.hashCode()]?.let { sid ->
                                            addHeader("SID", sid)
                                        }
                                    }
                                    .build()
                    val options = request.newBuilder().method(OPTIONS, request.body).build()
                    ioSafe { app.baseClient.newCall(options).await() }

                    return chain.proceed(newRequest)
                } else {
                    return chain.proceed(chain.request())
                }
            }
        }
    }

    private suspend fun getKey(): String {
        return app.get("https://raw.githubusercontent.com/enimax-anime/key/e6/key.txt").text
    }

    // #region - Data classes
    private data class Response(
            @JsonProperty("status") val status: Boolean,
            @JsonProperty("html") val html: String
    ) {
        fun getDocument(): Document {
            return Jsoup.parse(html)
        }
    }

    private data class ZoroSyncData(
            @JsonProperty("mal_id") val malId: String?,
            @JsonProperty("anilist_id") val aniListId: String?,
    )

    private data class RapidCloudResponse(@JsonProperty("link") val link: String)
    // #endregion - Data classes



    //Parser

    data class Hianime(
        val success: Boolean,
        val data: HianimeData,
    )

    data class HianimeData(
        val tracks: List<HianimeTrack>,
        val intro: Intro,
        val outro: Outro,
        val sources: List<HianimeSource>,
        @JsonProperty("anilistID")
        val anilistId: Long,
        @JsonProperty("malID")
        val malId: Long,
    )

    data class HianimeTrack(
        val file: String,
        val label: String?,
        val kind: String,
        val default: Boolean?,
    )

    data class Intro(
        val start: Long,
        val end: Long,
    )

    data class Outro(
        val start: Long,
        val end: Long,
    )

    data class HianimeSource(
        val url: String,
        val type: String,
    )
}
