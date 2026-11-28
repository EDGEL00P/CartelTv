package com.carteltv.tv.engine

import com.carteltv.tv.data.xtream.EpgSearchQuery
import com.carteltv.tv.engine.stability.BackgroundWorkScheduler
import com.carteltv.tv.engine.stability.DecoderBudget
import com.carteltv.tv.engine.stability.NavigationPause
import com.carteltv.tv.engine.stability.SecretVault
import com.carteltv.tv.update.ApkInstaller
import com.carteltv.tv.update.UpdateDexMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

/**
 * Unit verification for all 5 engine architectural changes:
 *  1. Embedded `.dm` ART profile selection/extraction + signer/version compatibility checks
 *  2. Hardware-only MediaCodecList + RAM concurrent-stream budgeting
 *  3. Playback- and navigation-aware background throttling
 *  4. AAD-bound AES-256-GCM SecretVault envelope (roundtrip + cross-scope rejection + tamper detection)
 *  5. FTS4 + materialized `search_words` query normalization & vocabulary matching
 */
class EngineFeaturesTest {

    @Test
    fun `selectFileName picks exact API or open-ended fallback`() {
        val index = Properties().apply {
            setProperty("28", "api28.dm")
            setProperty("31", "api31.dm")
            setProperty(Int.MAX_VALUE.toString(), "latest.dm")
        }
        assertEquals("api28.dm", UpdateDexMetadata.selectFileName(index, 28))
        assertEquals("api31.dm", UpdateDexMetadata.selectFileName(index, 31))
        assertEquals("latest.dm", UpdateDexMetadata.selectFileName(index, 35))
        assertNull(UpdateDexMetadata.selectFileName(index, 29))
    }

    @Test
    fun `extract reads matching dm entry from APK archive`() {
        val tmpDir = Files.createTempDirectory("cartel-dm-test").toFile()
        try {
            val fakeApk = File(tmpDir, "update.apk")
            val dmBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x11, 0x22, 0x33)
            ZipOutputStream(fakeApk.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry(UpdateDexMetadata.ASSET_DIR + UpdateDexMetadata.INDEX_FILE))
                zos.write("31=31.dm\n2147483647=max.dm\n".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry(UpdateDexMetadata.ASSET_DIR + "max.dm"))
                zos.write(dmBytes)
                zos.closeEntry()
            }

            val dest = File(tmpDir, "out.dm")
            val extracted = UpdateDexMetadata.extract(fakeApk, sdkInt = 34, destination = dest)
            assertNotNull(extracted)
            assertArrayEquals(dmBytes, extracted!!.readBytes())
            assertNull(UpdateDexMetadata.extract(fakeApk, sdkInt = 27, destination = File(tmpDir, "old.dm")))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `selfUpdater compatibility rejects downgrade, wrong package, and signer mismatch`() {
        val certA = setOf("3082abcd")
        val certB = setOf("3082ffff")
        assertTrue(ApkInstaller.isCompatibleSpec("com.carteltv.tv", "com.carteltv.tv", 7, 8, certA, certA))
        assertFalse(ApkInstaller.isCompatibleSpec("com.carteltv.tv", "com.carteltv.tv", 7, 7, certA, certA))
        assertFalse(ApkInstaller.isCompatibleSpec("com.carteltv.tv", "com.carteltv.tv", 7, 6, certA, certA))
        assertFalse(ApkInstaller.isCompatibleSpec("com.carteltv.tv", "com.other.app", 7, 8, certA, certA))
        assertFalse(ApkInstaller.isCompatibleSpec("com.carteltv.tv", "com.carteltv.tv", 7, 8, certA, certB))
        assertFalse(ApkInstaller.isCompatibleSpec("com.carteltv.tv", "com.carteltv.tv", 7, 8, emptySet(), emptySet()))
    }

    @Test
    fun `decoderBudget filters software codecs and enforces RAM and reserve-one caps`() {
        assertTrue(DecoderBudget.isSoftwareCodecName("OMX.google.h264.decoder"))
        assertTrue(DecoderBudget.isSoftwareCodecName("c2.android.hevc.decoder"))
        assertFalse(DecoderBudget.isSoftwareCodecName("OMX.Nvidia.h265.decode"))
        assertFalse(DecoderBudget.isSoftwareCodecName("c2.amlogic.hevc.decoder.awesome"))

        // 2 GB stick: capped at 2
        assertEquals(2, DecoderBudget.computeMaxStreams(8, 8, heapMb = 256, lowRam = false, physicalRamMb = 2048))
        // 3 GB Onn 4K Pro (384 MB heap): capped at 3
        assertEquals(3, DecoderBudget.computeMaxStreams(8, 8, heapMb = 384, lowRam = false, physicalRamMb = 3072))
        // 4 GB+ Shield Pro / big heap: capped at 4
        assertEquals(4, DecoderBudget.computeMaxStreams(16, 16, heapMb = 1024, lowRam = false, physicalRamMb = 4096))
        // Tight hardware decoder count reserves 1 for UI (3 - 1 = 2)
        assertEquals(2, DecoderBudget.computeMaxStreams(3, 6, heapMb = 1024, lowRam = false, physicalRamMb = 4096))
        // Unknown decoder count assumes 2 → reserves 1 → 1 stream
        assertEquals(1, DecoderBudget.computeMaxStreams(null, null, heapMb = 1024, lowRam = false, physicalRamMb = 4096))
    }

    @Test
    fun `backgroundWork tracks playback refcount, checkpoint deferral, and nav quiet window`() {
        BackgroundWorkScheduler.resetForTest()
        assertFalse(BackgroundWorkScheduler.isPlaybackActive)
        BackgroundWorkScheduler.importCheckpoint() // does not throw while idle

        BackgroundWorkScheduler.enterPlayback()
        BackgroundWorkScheduler.enterPlayback()
        assertTrue(BackgroundWorkScheduler.isPlaybackActive)
        var deferred = false
        try {
            BackgroundWorkScheduler.importCheckpoint()
        } catch (_: BackgroundWorkScheduler.PlaybackDeferred) {
            deferred = true
        }
        assertTrue(deferred)

        BackgroundWorkScheduler.exitPlayback()
        assertTrue(BackgroundWorkScheduler.isPlaybackActive) // still 1 open
        BackgroundWorkScheduler.exitPlayback()
        assertFalse(BackgroundWorkScheduler.isPlaybackActive)
        BackgroundWorkScheduler.exitPlayback() // floor at 0
        assertFalse(BackgroundWorkScheduler.isPlaybackActive)

        val now = 10_000_000_000L
        BackgroundWorkScheduler.onSeek(now)
        assertTrue(BackgroundWorkScheduler.isSeekSettling(now + 1_000_000_000L))
        assertFalse(BackgroundWorkScheduler.isSeekSettling(now + 2_500_000_000L))

        BackgroundWorkScheduler.onNavigation(now)
        assertTrue(BackgroundWorkScheduler.yieldToNavigation(now + 100_000_000L))
        assertFalse(BackgroundWorkScheduler.yieldToNavigation(now + 500_000_000L))

        val pause = NavigationPause(idleWindowNs = 2_000_000_000L) { now + 500_000_000L }
        pause.onNavigation(now)
        assertEquals(1500L, pause.remainingMs(now + 500_000_000L))
        assertEquals(0L, pause.remainingMs(now + 2_500_000_000L))
        BackgroundWorkScheduler.resetForTest()
    }

    @Test
    fun `vault enc2 AAD envelope roundtrips and rejects cross-profile substitution`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealed = SecretVault.sealWithKey("super-secret-token", key, "cartel.v2", "profile.p1.trakt")
        assertTrue(sealed.startsWith("enc2:"))
        assertEquals("super-secret-token", SecretVault.unsealWithKey(sealed, key, "cartel.v2", "profile.p1.trakt"))

        // Cross-profile AAD mismatch MUST fail GCM authentication
        var crossProfileRejected = false
        try {
            SecretVault.unsealWithKey(sealed, key, "cartel.v2", "profile.p2.trakt")
        } catch (_: AEADBadTagException) {
            crossProfileRejected = true
        }
        assertTrue("Expected AEADBadTagException on wrong AAD scope", crossProfileRejected)

        // Ciphertext tampering MUST fail GCM authentication
        val parts = sealed.removePrefix("enc2:").split('.', limit = 2)
        val rawCt = SecretVault.decodeBase64(parts[1]).also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val tampered = "enc2:${parts[0]}.${SecretVault.encodeBase64(rawCt)}"
        var tamperRejected = false
        try {
            SecretVault.unsealWithKey(tampered, key, "cartel.v2", "profile.p1.trakt")
        } catch (_: AEADBadTagException) {
            tamperRejected = true
        }
        assertTrue("Expected AEADBadTagException on tampered ciphertext", tamperRejected)
    }

    @Test
    fun `epgSearchQuery sanitizes FTS4 syntax and ranks prefix vocabulary matches first`() {
        assertEquals("champions* league* hd*", EpgSearchQuery.buildFtsMatch("Champions: League! (HD)"))
        assertEquals("nba* finals*", EpgSearchQuery.buildFtsMatch("\"NBA\" OR Finals*"))
        assertNull(EpgSearchQuery.buildFtsMatch("  a ! OR NOT "))

        val vocab = listOf("arsenal", "barcelona", "celtic", "star", "arsenic", "mars")
        val hits = EpgSearchQuery.matchVocabulary(vocab, "ars")
        // Prefix matches ("arsenal", "arsenic") must sort ahead of substring matches ("barcelona", "mars")
        assertEquals(listOf("arsenal", "arsenic", "mars"), hits)
    }
}
