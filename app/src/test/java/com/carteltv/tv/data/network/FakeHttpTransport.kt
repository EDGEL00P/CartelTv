package com.carteltv.tv.data.network

/** Records requested URLs and answers from a URL-substring -> body map; any unmatched URL throws like a dead network. */
class FakeHttpTransport(private val routes: Map<String, String>) : HttpTransport {
    val requested = mutableListOf<String>()
    val headersSeen = mutableListOf<Map<String, String>>()

    private fun answer(url: String, headers: Map<String, String> = emptyMap()): String {
        requested += url
        headersSeen += headers
        return routes.entries.firstOrNull { url.contains(it.key) }?.value
            ?: throw java.io.IOException("no route for $url")
    }

    override fun getBlocking(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int) = answer(url, headers)
    override suspend fun get(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int) = answer(url, headers)
    override suspend fun getBytes(url: String, headers: Map<String, String>, connectMs: Int, readMs: Int) =
        answer(url).toByteArray()

    override suspend fun request(
        url: String, method: String, headers: Map<String, String>, body: String?,
        contentType: String?, connectMs: Int, readMs: Int,
    ): Pair<Int, String> = 200 to answer(url, headers)

    override suspend fun requestFull(
        url: String, method: String, headers: Map<String, String>, body: String?,
        contentType: String?, connectMs: Int, readMs: Int,
    ): Triple<Int, Map<String, List<String>>, String> = Triple(200, emptyMap(), answer(url, headers))

    override suspend fun getStream(
        url: String, headers: Map<String, String>, connectMs: Int, readMs: Int,
    ): HttpResponseStream = HttpResponseStream(200, emptyMap(), answer(url, headers).byteInputStream())
}
