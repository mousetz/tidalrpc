package com.mousetz.tidalrpc

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.tidal.sdk.auth.TidalAuth
import com.tidal.sdk.auth.model.AuthConfig
import com.tidal.sdk.tidalapi.generated.TidalApiClient
import com.tidal.sdk.tidalapi.generated.models.AlbumsResourceObject
import com.tidal.sdk.tidalapi.generated.models.ArtworksResourceObject
import com.tidal.sdk.tidalapi.generated.models.ArtistsResourceObject
import com.tidal.sdk.tidalapi.generated.models.IncludedInner
import com.tidal.sdk.tidalapi.generated.models.TracksResourceObject
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

class TidalCatalog(context: Context) {
    private val auth = TidalAuth.getInstance(
        AuthConfig(clientId = BuildConfig.TIDAL_CLIENT_ID, credentialsKey = "tidalrpc"),
        context.applicationContext,
    )
    private val api = TidalApiClient(auth.credentialsProvider)
    private val cache = LruCache<String, Track>(32)

    val signedIn: Boolean get() = auth.credentialsProvider.isUserLoggedIn()

    fun loginUrl(): Uri = auth.auth.initializeLogin(REDIRECT_URI, null)

    suspend fun finishLogin(query: String): Boolean = auth.auth.finalizeLogin(query).isSuccess

    suspend fun logout() {
        auth.auth.logout()
        cache.evictAll()
    }

    suspend fun enrich(local: Track): Track? {
        cache.get(local.key)?.let { return it }
        val direct = if (local.mediaId?.matches(Regex("[0-9]+")) == true) {
            try {
                api.createTracks().tracksIdGet(local.mediaId, include = listOf("albums", "artists"))
                    .body()?.let { listOf(it.data) to it.included.orEmpty() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        } else null
        val candidates = direct ?: run {
            val search = api.createSearchResults().searchResultsGet(
                filterQuery = "${local.title} ${local.artist}",
                include = listOf("tracks"),
            ).body()
            val ids = search?.data?.firstOrNull()?.relationships?.tracks?.data
                ?.map { it.id }?.take(6).orEmpty()
            if (ids.isEmpty()) null else api.createTracks().tracksGet(
                filterId = ids,
                include = listOf("albums", "artists"),
            ).body()?.let { it.data to it.included.orEmpty() }
        } ?: return null

        val scored = candidates.first.map { candidate ->
            candidate to score(local, candidate, candidates.second)
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second < 140 || (scored.getOrNull(1)?.second ?: -1) >= best.second - 15) return null

        val matched = best.first
        val albumId = matched.relationships?.albums?.data?.firstOrNull()?.id
        val artistId = matched.relationships?.artists?.data?.firstOrNull()?.id
        val album = candidates.second.filterIsInstance<AlbumsResourceObject>()
            .firstOrNull { it.id == albumId }?.attributes?.title ?: local.album
        val albumImage = albumId?.let { id ->
            try {
                val response = api.createAlbums().albumsIdGet(id, include = listOf("coverArt")).body()
                artworkUrl(response?.included, response?.data?.relationships?.coverArt?.data?.firstOrNull()?.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        } ?: local.albumImage
        val artistImage = artistId?.let { id ->
            try {
                val response = api.createArtists().artistsIdGet(id, include = listOf("profileArt")).body()
                artworkUrl(response?.included, response?.data?.relationships?.profileArt?.data?.firstOrNull()?.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        val resolved = local.copy(
            album = album,
            albumImage = albumImage ?: local.albumImage,
            artistImage = artistImage,
            url = "https://tidal.com/browse/track/${matched.id}",
        )
        cache.put(local.key, resolved)
        return resolved
    }

    private fun score(local: Track, candidate: TracksResourceObject, included: List<IncludedInner>): Int {
        val title = candidate.attributes?.title ?: return 0
        if (normalize(title) != normalize(local.title)) return 0
        var points = 100
        val artistIds = candidate.relationships?.artists?.data.orEmpty().map { it.id }
        val names = included.filterIsInstance<ArtistsResourceObject>()
            .filter { it.id in artistIds }.mapNotNull { it.attributes?.name }
        if (names.any { normalize(local.artist).contains(normalize(it)) }) points += 40
        val albumIds = candidate.relationships?.albums?.data.orEmpty().map { it.id }
        if (local.album != null && included.filterIsInstance<AlbumsResourceObject>()
                .any { it.id in albumIds && normalize(it.attributes?.title.orEmpty()) == normalize(local.album) }) {
            points += 20
        }
        val seconds = candidate.attributes?.duration?.let { runCatching { java.time.Duration.parse(it).seconds }.getOrNull() }
        if (seconds != null && local.durationMs != null && abs(seconds * 1000 - local.durationMs) <= 5_000) points += 20
        return points
    }

    private fun artworkUrl(included: List<IncludedInner>?, id: String?): String? =
        included.orEmpty().filterIsInstance<ArtworksResourceObject>()
            .firstOrNull { it.id == id }?.attributes?.files
            ?.filter { it.href.startsWith("https://") }
            ?.maxByOrNull { it.meta.width * it.meta.height }?.href

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        const val REDIRECT_URI = "com.mousetz.tidalrpc://oauth/callback"
    }
}
