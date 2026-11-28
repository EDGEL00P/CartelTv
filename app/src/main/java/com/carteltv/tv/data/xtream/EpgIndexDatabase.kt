package com.carteltv.tv.data.xtream

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.carteltv.tv.AppScope
import com.carteltv.tv.engine.stability.BackgroundWorkScheduler
import com.carteltv.tv.data.network.HttpSupport
import java.io.File
import java.lang.System
import kotlinx.coroutines.launch

/**
 * Pure query-token helper for FTS4 + materialized `search_words` EPG lookups (JVM-testable).
 */
object EpgSearchQuery {
    private val nonWord = Regex("[^\\p{L}\\p{Nd}]+")
    private val ftsOperators = setOf("and", "or", "not", "near")

    fun normalizeTokens(raw: String): List<String> =
        raw.trim().lowercase().split(nonWord).filter { it.length >= 2 && it !in ftsOperators }.distinct()

    /** Builds a safe FTS4 prefix query (`tok1* tok2*`) without FTS syntax-injection risk. */
    fun buildFtsMatch(raw: String): String? {
        val tokens = normalizeTokens(raw)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /** Matches a substring/prefix query against materialized `search_words` terms. */
    fun matchVocabulary(terms: Collection<String>, query: String, limit: Int = 32): List<String> {
        val tokens = normalizeTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val last = tokens.last()
        return terms.asSequence()
            .filter { it.contains(last, ignoreCase = true) }
            .sortedWith(compareBy<String> { !it.startsWith(last, ignoreCase = true) }.thenBy { it.length }.thenBy { it })
            .take(limit)
            .toList()
    }
}

/**
 * Indexed Live TV channel & EPG store using SQLite WAL + incremental auto-vacuum + external-content
 * FTS4 (`program_search`) + `fts4aux` materialized into `search_words` once per commit.
 *
 * Materializing `search_words` avoids re-decoding FTS4 posting lists on every substring/team
 * search while WAL keeps live guide reads lock-free during background updates.
 */
class EpgIndexDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class ProgramHit(
        val streamId: Long,
        val channelName: String,
        val title: String,
        val description: String?,
        val startMs: Long,
        val stopMs: Long,
    )

    private val stageMarker = File(context.applicationContext.filesDir, "epg-stage-in-progress")
    private val autoVacuumMarker = File(context.applicationContext.filesDir, "epg-auto-vacuum-pending")

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (db.version == 0) {
            db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
        ensureSearchIndex(db, rebuild = false)
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channels (
                stream_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                epg_id TEXT,
                icon TEXT,
                num INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS programs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                stream_id INTEGER NOT NULL,
                start_ms INTEGER NOT NULL,
                stop_ms INTEGER NOT NULL,
                title TEXT NOT NULL,
                descr TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_stream_start ON programs(stream_id, start_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_stream_stop_start ON programs(stream_id, stop_ms, start_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_epg ON channels(epg_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS epg_alias(stream_id INTEGER PRIMARY KEY, epg_id TEXT NOT NULL)")
    }

    /** v1→v2 repairs the schema without discarding the viewer's channels or programme guide. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createTables(db)
        ensureSearchIndex(db, rebuild = true)
        if (oldVersion < 2) autoVacuumMarker.createNewFile()
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.isReadOnly) return
        db.execSQL("CREATE TABLE IF NOT EXISTS epg_alias(stream_id INTEGER PRIMARY KEY, epg_id TEXT NOT NULL)")
        // Existing installs predate this index; IF NOT EXISTS adds it without wiping the guide on open.
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_stream_stop_start ON programs(stream_id, stop_ms, start_ms)")
        // epg_id lookups (providerEpgIds, epgIdIndex) previously scanned channels table-wide.
        db.execSQL("CREATE INDEX IF NOT EXISTS channels_epg ON channels(epg_id)")
        ensureSearchIndex(db, rebuild = true)
        if (autoVacuumMarker.exists()) {
            // PRAGMA auto_vacuum only takes effect after VACUUM, which cannot run inside onUpgrade's transaction.
            AppScope.io.launch { enableAutoVacuum(writableDatabase) }
        }
        if (stageMarker.exists()) {
            stageMarker.delete()
            // A crashed staged import can leave a large WAL/freelist. Reclaim it without blocking the open/UI thread.
            AppScope.io.launch { compactIfWasteful(writableDatabase) }
        }
    }

    private fun ensureSearchIndex(db: SQLiteDatabase, rebuild: Boolean): Boolean {
        db.execSQL("CREATE TABLE IF NOT EXISTS search_words(term TEXT PRIMARY KEY)")
        val hasTrigger = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='program_search_insert'",
            null,
        ).use { it.moveToFirst() }
        if (hasTrigger) return false

        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS program_search USING fts4(title, descr, content='programs', tokenize=unicode61)",
        )
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS program_terms USING fts4aux(program_search)")
        db.execSQL(
            "CREATE TRIGGER program_search_insert AFTER INSERT ON programs BEGIN " +
                "INSERT INTO program_search(docid, title, descr) VALUES(new.id, new.title, new.descr); END",
        )
        db.execSQL(
            "CREATE TRIGGER program_search_delete BEFORE DELETE ON programs BEGIN " +
                "DELETE FROM program_search WHERE docid=old.id; END",
        )
        db.execSQL(
            "CREATE TRIGGER program_search_update_before BEFORE UPDATE ON programs BEGIN " +
                "DELETE FROM program_search WHERE docid=old.id; END",
        )
        db.execSQL(
            "CREATE TRIGGER program_search_update_after AFTER UPDATE ON programs BEGIN " +
                "INSERT INTO program_search(docid, title, descr) VALUES(new.id, new.title, new.descr); END",
        )
        if (rebuild) {
            db.execSQL("INSERT INTO program_search(program_search) VALUES('rebuild')")
            refreshSearchWords(db)
        }
        return true
    }

    /** Materializes unique FTS4 terms once per write commit so queries avoid fts4aux decoding.
     *
     * SQLite truncates/rebuilds this table efficiently; a differential NOT IN variant measured
     * roughly twice as slow on an unchanged vocabulary, so keep the simple rebuild and trace it.
     */
    private fun refreshSearchWords(db: SQLiteDatabase) {
        val started = System.nanoTime()
        db.execSQL("DELETE FROM search_words")
        db.execSQL("INSERT OR IGNORE INTO search_words(term) SELECT term FROM program_terms WHERE col='*'")
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms > 50) Log.w("EpgDb", "refreshSearchWords took ${ms} ms (write amplification risk)")
    }

    fun upsertChannels(streams: List<XtreamClient.LiveStream>) {
        if (streams.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO channels(stream_id, name, epg_id, icon, num) VALUES(?,?,?,?,?)",
            )
            streams.forEachIndexed { idx, s ->
                if (idx % 256 == 0) BackgroundWorkScheduler.yieldToNavigation()
                stmt.clearBindings()
                stmt.bindLong(1, s.streamId)
                stmt.bindString(2, s.name)
                if (s.epgId != null) stmt.bindString(3, s.epgId) else stmt.bindNull(3)
                if (s.icon != null) stmt.bindString(4, s.icon) else stmt.bindNull(4)
                stmt.bindLong(5, s.num.toLong())
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Gives each slot channel that has no guide id the one programme its name announces (see [EventSlots]). */
    fun replaceEventSlots(streams: List<XtreamClient.LiveStream>) {
        val slots = streams.mapNotNull { s ->
            if (!s.epgId.isNullOrBlank()) null else EventSlots.parse(s.name)?.let { s.streamId to it }
        }
        if (slots.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                "INSERT INTO programs(stream_id, start_ms, stop_ms, title, descr) VALUES(?,?,?,?,NULL)",
            )
            for ((id, ev) in slots) {
                db.delete("programs", "stream_id = ?", arrayOf(id.toString()))
                insert.clearBindings()
                insert.bindLong(1, id)
                insert.bindLong(2, ev.startMs)
                insert.bindLong(3, ev.startMs + EventSlots.DEFAULT_MS)
                insert.bindString(4, ev.title)
                insert.executeInsert()
            }
            refreshSearchWords(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceProgramsForStream(streamId: Long, entries: List<XtreamClient.EpgEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        runCatching { stageMarker.createNewFile() }
        db.beginTransaction()
        try {
            db.delete("programs", "stream_id = ?", arrayOf(streamId.toString()))
            val stmt = db.compileStatement(
                "INSERT INTO programs(stream_id, start_ms, stop_ms, title, descr) VALUES(?,?,?,?,?)",
            )
            for (e in entries) {
                val title = e.title?.trim().orEmpty()
                if (title.isBlank()) continue
                val startMs = (e.startTs?.toLongOrNull() ?: 0L) * 1000L
                val stopMs = (e.stopTs?.toLongOrNull() ?: 0L) * 1000L
                stmt.clearBindings()
                stmt.bindLong(1, streamId)
                stmt.bindLong(2, startMs)
                stmt.bindLong(3, stopMs)
                stmt.bindString(4, title)
                val descr = e.description?.trim()?.takeIf { it.isNotBlank() }
                if (descr != null) stmt.bindString(5, descr) else stmt.bindNull(5)
                stmt.executeInsert()
            }
            refreshSearchWords(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            stageMarker.delete()
        }
    }

    /** Every channel as the matcher sees it. */
    fun channelsForMatching(): List<EpgMatcher.Channel> {
        val out = ArrayList<EpgMatcher.Channel>()
        readableDatabase.rawQuery("SELECT stream_id, name, epg_id FROM channels", null).use { c ->
            while (c.moveToNext()) out += EpgMatcher.Channel(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))
        }
        return out
    }

    /** Every guide id the provider gave its channels (`epg_channel_id`), as stored. Empty until Live TV has loaded once. */
    fun providerEpgIds(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT epg_id FROM channels WHERE epg_id IS NOT NULL AND epg_id != ''", null,
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /** Replaces the guide ids matched by name for channels that had no usable id of their own. */
    fun replaceAliases(aliases: Map<Long, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM epg_alias")
            val stmt = db.compileStatement("INSERT INTO epg_alias(stream_id, epg_id) VALUES(?,?)")
            for ((sid, id) in aliases) {
                stmt.clearBindings(); stmt.bindLong(1, sid); stmt.bindString(2, id); stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Lower-cased guide id to every stream that carries it: by its own `epg_channel_id` (HD/SD variants share one) or by a name match. */
    fun epgIdIndex(): Map<String, List<Long>> {
        val out = HashMap<String, MutableList<Long>>()
        readableDatabase.rawQuery("SELECT stream_id, lower(epg_id) FROM epg_alias", null).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(1)) { ArrayList() }.add(c.getLong(0))
        }
        readableDatabase.rawQuery(
            "SELECT stream_id, lower(epg_id) FROM channels WHERE epg_id IS NOT NULL AND epg_id != ''", null,
        ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(1)) { ArrayList() }.add(c.getLong(0)) }
        return out
    }

    /** Programmes stored for one channel that have not ended before [sinceMs], oldest first. */
    fun programsFor(streamId: Long, sinceMs: Long): List<XtreamClient.EpgEntry> {
        val out = ArrayList<XtreamClient.EpgEntry>()
        readableDatabase.rawQuery(
            "SELECT title, descr, start_ms, stop_ms FROM programs WHERE stream_id=? AND stop_ms>? ORDER BY start_ms",
            arrayOf(streamId.toString(), sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += XtreamClient.EpgEntry(
                    title = c.getString(0),
                    description = if (c.isNull(1)) null else c.getString(1),
                    startTs = (c.getLong(2) / 1000L).toString(),
                    stopTs = (c.getLong(3) / 1000L).toString(),
                )
            }
        }
        return out
    }

    /** Plain staging table with no FTS triggers; commits every [BATCH] rows so guide reads and writes are never starved. */
    inner class Stage internal constructor() : AutoCloseable {
        private val db = writableDatabase
        private val stmt: android.database.sqlite.SQLiteStatement
        private var pending = 0

        init {
            db.execSQL("DROP TABLE IF EXISTS xmltv_stage")
            db.execSQL(
                "CREATE TABLE xmltv_stage(epg_id TEXT NOT NULL, start_ms INTEGER NOT NULL, stop_ms INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT)",
            )
            stmt = db.compileStatement("INSERT INTO xmltv_stage(epg_id, start_ms, stop_ms, title, descr) VALUES(?,?,?,?,?)")
            db.beginTransaction()
        }

        fun add(epgId: String, startMs: Long, stopMs: Long, title: String, descr: String?) {
            stmt.clearBindings()
            stmt.bindString(1, epgId)
            stmt.bindLong(2, startMs)
            stmt.bindLong(3, stopMs)
            stmt.bindString(4, title)
            if (descr != null) stmt.bindString(5, descr) else stmt.bindNull(5)
            stmt.executeInsert()
            if (++pending >= BATCH) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                pending = 0
            }
        }

        override fun close() {
            db.setTransactionSuccessful()
            db.endTransaction()
            stmt.close()
        }
    }

    fun openStage(): Stage = Stage()

    fun dropStage() { writableDatabase.execSQL("DROP TABLE IF EXISTS xmltv_stage") }

    /**
     * Swaps staged programmes in, a hundred XMLTV channels per transaction. Only channels present in the stage are
     * touched, so a channel the file doesn't cover keeps whatever it had.
     */
    fun applyStage(pruneBeforeMs: Long) {
        val db = writableDatabase
        db.execSQL("CREATE INDEX IF NOT EXISTS xmltv_stage_epg ON xmltv_stage(epg_id)")
        val streams = epgIdIndex()
        val ids = ArrayList<String>()
        db.rawQuery("SELECT DISTINCT epg_id FROM xmltv_stage", null).use { c -> while (c.moveToNext()) ids += c.getString(0) }
        runCatching { stageMarker.createNewFile() }
        try {
            for (chunk in ids.chunked(100)) {
                BackgroundWorkScheduler.yieldToNavigation()
                val streamIds = chunk.flatMap { streams[it].orEmpty() }
                if (streamIds.isEmpty()) continue
                db.beginTransaction()
                try {
                    db.execSQL(
                        "DELETE FROM programs WHERE stream_id IN (${streamIds.joinToString(",")})",
                    )
                    val ins = db.compileStatement(
                        "INSERT INTO programs(stream_id, start_ms, stop_ms, title, descr) VALUES(?,?,?,?,?)",
                    )
                    db.rawQuery(
                        "SELECT epg_id, start_ms, stop_ms, title, descr FROM xmltv_stage WHERE epg_id IN (${chunk.joinToString(",") { "?" }})",
                        chunk.toTypedArray(),
                    ).use { c ->
                        while (c.moveToNext()) {
                            for (sid in streams[c.getString(0)].orEmpty()) {
                                ins.clearBindings()
                                ins.bindLong(1, sid)
                                ins.bindLong(2, c.getLong(1))
                                ins.bindLong(3, c.getLong(2))
                                ins.bindString(4, c.getString(3))
                                if (c.isNull(4)) ins.bindNull(5) else ins.bindString(5, c.getString(4))
                                ins.executeInsert()
                            }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            db.execSQL("DELETE FROM programs WHERE stop_ms < ?", arrayOf<Any>(pruneBeforeMs))
            db.execSQL("DROP TABLE IF EXISTS xmltv_stage")
            refreshSearchWords(db)
        } finally {
            stageMarker.delete()
        }
    }

    fun searchPrograms(rawQuery: String, limit: Int = 30): List<ProgramHit> {
        val started = System.nanoTime()
        val fts = EpgSearchQuery.buildFtsMatch(rawQuery) ?: return emptyList()
        val db = readableDatabase
        val hits = ArrayList<ProgramHit>()
        val sql = """
            SELECT p.stream_id, COALESCE(c.name, 'Channel ' || p.stream_id), p.title, p.descr, p.start_ms, p.stop_ms
            FROM program_search s
            JOIN programs p ON p.id = s.docid
            LEFT JOIN channels c ON c.stream_id = p.stream_id
            WHERE program_search MATCH ?
            ORDER BY p.start_ms ASC
            LIMIT ?
        """.trimIndent()
        db.rawQuery(sql, arrayOf(fts, limit.toString())).use { cur ->
            while (cur.moveToNext()) {
                hits += ProgramHit(
                    streamId = cur.getLong(0),
                    channelName = cur.getString(1),
                    title = cur.getString(2),
                    description = if (cur.isNull(3)) null else cur.getString(3),
                    startMs = cur.getLong(4),
                    stopMs = cur.getLong(5),
                )
            }
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms > 100) Log.w("EpgDb", "searchPrograms('${HttpSupport.redact(rawQuery).take(40)}') took ${ms} ms, hits=${hits.size}")
        return hits
    }

private fun enableAutoVacuum(db: SQLiteDatabase) {
        runCatching {
            if (pragmaLong(db, "PRAGMA auto_vacuum") != 2L) {
                db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                db.execSQL("VACUUM")
            }
            autoVacuumMarker.delete()
        }
    }

    fun compactIfWasteful(db: SQLiteDatabase = writableDatabase) {
        runCatching {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val pageSize = pragmaLong(db, "PRAGMA page_size")
            val freePages = pragmaLong(db, "PRAGMA freelist_count")
            val autoVac = pragmaLong(db, "PRAGMA auto_vacuum")
            if (freePages * pageSize >= MIN_VACUUM_BYTES && autoVac == 2L) {
                db.rawQuery("PRAGMA incremental_vacuum(4096)", null).use { cur ->
                    while (cur.moveToNext()) { /* drain reclamation steps */ }
                }
            }
        }
    }

    private fun pragmaLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    companion object {
        private const val DB_NAME = "cartel_epg_index.db"
        private const val DB_VERSION = 2
        private const val MIN_VACUUM_BYTES = 1L * 1024L * 1024L
        private const val BATCH = 2000

        @Volatile private var instance: EpgIndexDatabase? = null

        fun get(context: Context): EpgIndexDatabase =
            instance ?: synchronized(this) {
                instance ?: EpgIndexDatabase(context).also { instance = it }
            }
    }
}
