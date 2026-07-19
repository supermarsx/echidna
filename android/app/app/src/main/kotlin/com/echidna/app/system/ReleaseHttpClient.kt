package com.echidna.app.system

import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * The tiny transport the release downloader needs, behind an interface so the whole download and
 * verification chain can be exercised in unit tests without touching the network. No third-party
 * HTTP dependency is added: [HttpsReleaseHttpClient] is `javax.net.ssl.HttpsURLConnection` only.
 */
interface ReleaseHttpClient {
    /** Fetches a small text document (release JSON, checksum list). Throws on any failure. */
    fun fetchText(url: String, maxBytes: Int): String

    /** Streams a binary asset to [destination] and returns the byte count. Throws on any failure. */
    fun download(url: String, destination: File, maxBytes: Long): Long
}

/**
 * HTTPS-only transport.
 *
 * Two deliberate hardenings over a plain `URL.openStream()`:
 *
 *  * **Scheme** — every URL, including each redirect hop, must be `https`. Redirects are followed
 *    manually instead of by `HttpURLConnection` so an `http://` `Location` is a hard failure with a
 *    named reason rather than a silent downgrade or an opaque protocol error.
 *  * **Host** — hops are confined to GitHub's own domains. Integrity and origin are already
 *    enforced by the digest and certificate checks, so this is defence in depth, but it means a
 *    redirect to an unrelated host is refused before a single byte is written to disk.
 */
class HttpsReleaseHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : ReleaseHttpClient {

    override fun fetchText(url: String, maxBytes: Int): String = openStream(url) { connection ->
        connection.inputStream.use { input ->
            String(input.readAtMost(maxBytes), StandardCharsets.UTF_8)
        }
    }

    override fun download(url: String, destination: File, maxBytes: Long): Long = openStream(url) { connection ->
        var written = 0L
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > maxBytes) {
                        // Stop before an unbounded body can fill the cache partition. The partial
                        // file is deleted by the caller's failure handling.
                        throw ReleaseArtifactException(
                            "Download refused: the artifact exceeded the ${maxBytes / (1024 * 1024)} MB " +
                                "size limit."
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        written
    }

    private fun <T> openStream(url: String, body: (HttpsURLConnection) -> T): T {
        var current = requireHttpsUrl(url)
        var hops = 0
        while (true) {
            val connection = (
                runCatching { current.openConnection() }.getOrElse { error ->
                    throw ReleaseArtifactException("Download failed: could not connect to $current (${error.reason()}).")
                }
                ) as? HttpsURLConnection
                ?: throw ReleaseArtifactException("Download refused: $current did not open an HTTPS connection.")
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            // Followed by hand below so a cross-scheme or cross-host hop fails loudly.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/octet-stream, application/json, */*")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = try {
                connection.responseCode
            } catch (error: IOException) {
                connection.disconnect()
                throw ReleaseArtifactException("Download failed: no response from $current (${error.reason()}).")
            }
            if (status in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw ReleaseArtifactException("Download failed: $current sent a redirect with no target.")
                }
                if (++hops > MAX_REDIRECTS) {
                    throw ReleaseArtifactException("Download failed: too many redirects starting at $url.")
                }
                current = requireHttpsUrl(runCatching { URL(current, location).toString() }.getOrElse { location })
                continue
            }
            if (status !in 200..299) {
                connection.disconnect()
                throw ReleaseArtifactException("Download failed: $current returned HTTP $status.")
            }
            return try {
                body(connection)
            } catch (error: IOException) {
                throw ReleaseArtifactException("Download failed while reading $current (${error.reason()}).")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "Echidna-Companion"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * Parses [raw] and requires HTTPS on a GitHub-owned host. Exposed to the package so the release
 * repository can reject a hostile `browser_download_url` from the API response itself, not only the
 * URLs it composes: the API is the very thing we are refusing to trust blindly.
 */
internal fun requireHttpsUrl(raw: String): URL {
    val url = runCatching { URL(raw) }.getOrElse {
        throw ReleaseArtifactException("Download refused: \"$raw\" is not a valid URL.")
    }
    if (!url.protocol.equals("https", ignoreCase = true)) {
        throw ReleaseArtifactException(
            "Download refused: $raw is not HTTPS. Release artifacts are only ever fetched over " +
                "HTTPS, even when the release API offers another scheme."
        )
    }
    val host = url.host.orEmpty().lowercase(Locale.ROOT)
    val allowed = ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    if (!allowed) {
        throw ReleaseArtifactException(
            "Download refused: $raw points at \"$host\", which is not a GitHub release host."
        )
    }
    return url
}

private val ALLOWED_HOST_SUFFIXES = listOf("github.com", "githubusercontent.com")
