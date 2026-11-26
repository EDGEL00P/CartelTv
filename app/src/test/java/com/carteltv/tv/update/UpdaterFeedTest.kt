package com.carteltv.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterFeedTest {
    private val sha = "a".repeat(64)

    @Test
    fun reads_the_update_json_the_release_workflow_publishes() {
        val feed = ReleaseUpdater.parseUpdateFeed(
            """{"versionName":"0.9.0","versionCode":9,"apkUrl":"https://example.test/CartelTV-v0.9.0.apk","sha256":"$sha"}""",
        )
        assertNotNull(feed)
        val release = feed!!.toRelease()
        assertEquals(9, release.versionCode)
        assertEquals("0.9.0", release.versionName)
        assertEquals(sha, release.sha256)
    }

    @Test
    fun a_semver_tag_does_not_matter_because_the_code_comes_from_versionCode() {
        val feed = ReleaseUpdater.parseUpdateFeed("""{"versionName":"3.8.0","versionCode":12,"apkUrl":"https://example.test/a.apk"}""")
        assertEquals(12, feed!!.versionCode)
    }

    @Test
    fun rejects_http_urls_and_missing_codes() {
        assertNull(ReleaseUpdater.parseUpdateFeed("""{"versionCode":9,"apkUrl":"http://example.test/a.apk"}"""))
        assertNull(ReleaseUpdater.parseUpdateFeed("""{"versionName":"1","apkUrl":"https://example.test/a.apk"}"""))
    }

    @Test
    fun leaves_the_github_release_shape_to_the_old_parser() {
        assertNull(ReleaseUpdater.parseUpdateFeed("""{"tag_name":"v0.9.0","assets":[{"name":"a.apk"}]}"""))
        assertNull(ReleaseUpdater.parseUpdateFeed("not json"))
    }

    @Test
    fun drops_a_malformed_checksum() {
        val release = ReleaseUpdater.parseUpdateFeed(
            """{"versionCode":9,"apkUrl":"https://example.test/a.apk","sha256":"nope"}""",
        )!!.toRelease()
        assertEquals("", release.sha256)
    }

    private val feedJson = """
        {"versionName":"0.9.0","versionCode":9,
         "apkUrl":"https://example.test/universal.apk","sha256":"$sha",
         "apks":{
           "universal":{"url":"https://example.test/universal.apk","sha256":"$sha"},
           "arm64-v8a":{"url":"https://example.test/arm64.apk","sha256":"${"b".repeat(64)}"},
           "armeabi-v7a":{"url":"https://example.test/armv7.apk","sha256":"${"c".repeat(64)}"}}}
    """.trimIndent()

    @Test
    fun picks_the_device_abi_in_preference_order() {
        val feed = ReleaseUpdater.parseUpdateFeed(feedJson)!!
        val r = feed.toRelease(listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("https://example.test/arm64.apk", r.apkUrl)
        assertEquals("b".repeat(64), r.sha256)
    }

    @Test
    fun falls_back_to_the_next_supported_abi() {
        val feed = ReleaseUpdater.parseUpdateFeed(feedJson)!!
        val r = feed.toRelease(listOf("x86_64", "armeabi-v7a"))
        assertEquals("https://example.test/armv7.apk", r.apkUrl)
    }

    @Test
    fun unknown_abis_fall_back_to_universal() {
        val feed = ReleaseUpdater.parseUpdateFeed(feedJson)!!
        val r = feed.toRelease(listOf("mips"))
        assertEquals("https://example.test/universal.apk", r.apkUrl)
        assertEquals(sha, r.sha256)
    }

    @Test
    fun no_abis_at_all_uses_the_universal_fields() {
        val feed = ReleaseUpdater.parseUpdateFeed(feedJson)!!
        val r = feed.toRelease()
        assertEquals("https://example.test/universal.apk", r.apkUrl)
    }

    @Test
    fun a_feed_without_the_apks_map_still_parses() {
        val feed = ReleaseUpdater.parseUpdateFeed(
            """{"versionName":"0.9.0","versionCode":9,"apkUrl":"https://example.test/u.apk","sha256":"$sha"}""",
        )!!
        assertEquals("https://example.test/u.apk", feed.toRelease(listOf("arm64-v8a")).apkUrl)
    }
}
