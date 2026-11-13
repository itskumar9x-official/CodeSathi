package com.inyogeshwar.codesathi.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A coding session: one project folder + one model + a chat history. */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String,
    val title: String,
    /** Display name of the folder (e.g. "AgentMT"), never a raw path typed by the user. */
    val folderName: String,
    /** SAF tree URI string (persistable grant), e.g. content://com.android.externalstorage.documents/tree/primary%3AAgentMT */
    val projectUri: String,
    val providerId: String,
    val modelId: String,
    /** "plan" or "build" */
    val agentMode: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** "active" | "done" */
    val status: String = "active",
    /** Pinned sessions sort first on Home (Phase 5, Step 4). Default
     *  false — the v3 → v4 migration adds this column with 0. */
    val pinned: Boolean = false
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** "user" | "assistant" | "tool" | "system" */
    val role: String,
    val text: String,
    /** Optional JSON describing a tool call (name, args, result) — kept for history. */
    val toolJson: String? = null,
    /**
     * Attached images (Phase 6, Step 2): a JSON array of file NAMES in
     * getFilesDir()/images/ (see [com.inyogeshwar.codesathi.agent.ImageStore]),
     * e.g. `["img_….jpg"]`. Only names — never bytes, never a content
     * URI — live in Room; the image files themselves are app-private and
     * are never written into a session backup. Null / missing = no images.
     */
    val imagesJson: String? = null,
    val createdAt: Long
)

@Entity(tableName = "favourite_models")
data class FavouriteModel(
    @PrimaryKey val modelKey: String, // "providerId/modelId"
    val providerId: String,
    val modelId: String,
    val starredAt: Long
)

@Entity(tableName = "session_summaries")
data class SessionSummary(
    @PrimaryKey val sessionId: String,
    val summaryMarkdown: String,
    val coversUpToMessageId: Long,
    val modelId: String,
    val createdAt: Long
)

/**
 * One model id fetched from GET {baseUrl}/models for a provider.
 * Cached so the picker still works offline / when the fetch fails.
 * Composite key: one row per (provider, model).
 */
@Entity(tableName = "cached_models", primaryKeys = ["providerId", "modelId"])
data class CachedModel(
    val providerId: String,
    val modelId: String,
    val fetchedAt: Long
)

@Dao
interface SessionDao {
    /**
     * Home list order (Phase 5, Step 4): pinned sessions first,
     * then most recently active — the updatedAt order is unchanged
     * for unpinned sessions.
     */
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, updatedAt DESC")
    fun observeSessions(): Flow<List<Session>>

    /**
     * Recency order only, no pinning — for the terminal / Git / editor
     * screens, whose fallback is "the most recent session", not "the
     * most recent pinned session". Home is the only list that sorts
     * pinned first.
     */
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessionsByRecency(): Flow<List<Session>>

    /**
     * Search sessions by title (Phase 5, Step 4): case-insensitive
     * LIKE — SQLite LIKE is case-insensitive for ASCII. The caller
     * escapes % and _ in the query, and ESCAPE '\' makes them
     * literal, so a "%" typed in the search box matches a literal
     * "%" in a title instead of matching everything.
     */
    @Query(
        "SELECT * FROM sessions WHERE title LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY pinned DESC, updatedAt DESC"
    )
    fun searchSessions(query: String): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): Session?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: Session)

    /**
     * Plain insert for backup import (Phase 5, Step 3): ABORT on a
     * duplicate id, so an import can never silently overwrite an
     * existing session — the caller checks first and skips duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: Session)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sessions SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE sessions SET agentMode = :mode WHERE id = :id")
    suspend fun setMode(id: String, mode: String)

    @Query("UPDATE sessions SET providerId = :providerId, modelId = :modelId WHERE id = :id")
    suspend fun setModel(id: String, providerId: String, modelId: String)

    /** Pin / unpin a session (Phase 5, Step 4). Does not touch
     *  updatedAt — pinning must not reorder by activity. */
    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /** Rename a session (Phase 5, Step 4). Does not touch updatedAt —
     *  "Last activity" stays about the chat, not the rename. */
    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("DELETE FROM messages WHERE sessionId = :id")
    suspend fun deleteMessagesForSession(id: String)

    @Query("DELETE FROM session_summaries WHERE sessionId = :id")
    suspend fun deleteSummaryForSession(id: String)

    @Query("DELETE FROM usage_records WHERE sessionId = :id")
    suspend fun deleteUsageForSession(id: String)

    /**
     * Delete a session and everything that belongs to it (Phase 5,
     * Step 4): its messages, its compaction summary, and its usage
     * rows, so the dashboard never shows orphan rows for a session
     * that no longer exists. One transaction — a failure part-way
     * leaves the session untouched instead of half-deleted.
     */
    @androidx.room.Transaction
    suspend fun deleteSessionAndData(id: String) {
        deleteMessagesForSession(id)
        deleteSummaryForSession(id)
        deleteUsageForSession(id)
        deleteById(id)
    }

    @Delete
    suspend fun delete(session: Session)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeMessages(sessionId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getMessages(sessionId: String): List<Message>

    @Insert
    suspend fun insert(message: Message): Long

    /** Delete one session's messages — backup import cleanup (Phase 5, Step 3). */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourite_models ORDER BY starredAt DESC")
    fun observeFavourites(): Flow<List<FavouriteModel>>

    @Query("SELECT * FROM favourite_models WHERE modelKey = :key")
    suspend fun get(key: String): FavouriteModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun star(favourite: FavouriteModel)

    @Query("DELETE FROM favourite_models WHERE modelKey = :key")
    suspend fun unstar(key: String)
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM session_summaries WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): SessionSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(summary: SessionSummary)
}

/**
 * One model turn's token usage (Phase 5, Step 2). One row per turn —
 * never aggregated in place — so every total the dashboard shows is a
 * SUM over stored rows, never a number that only lived in memory.
 * [estimated] is true when the provider sent no usage and the counts
 * are the chars÷4 estimate: the dashboard shows those rows as
 * estimates, never as real usage. A turn that fails (limit error,
 * network error) writes no row — no tokens were returned.
 */
@Entity(
    tableName = "usage_records",
    indices = [Index("sessionId"), Index("providerId", "modelId")]
)
data class UsageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val providerId: String,
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    /** True when the counts are the chars÷4 estimate, not provider-reported. */
    val estimated: Boolean,
    val createdAt: Long
)

/** Totals over a set of usage rows (one row, always returned, zeros when empty). */
data class UsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** Turns whose counts are estimates (provider sent no usage). */
    val estimatedTurns: Long = 0,
    val turns: Long = 0
)

/** Usage summed per (session, provider, model) — the dashboard groups these. */
data class UsageBySessionModel(
    val sessionId: String,
    val providerId: String,
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedTurns: Long,
    val turns: Long
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(record: UsageRecord): Long

    @Query(
        "SELECT COALESCE(SUM(promptTokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completionTokens), 0) AS completionTokens, " +
            "COALESCE(SUM(CASE WHEN estimated THEN 1 ELSE 0 END), 0) AS estimatedTurns, " +
            "COUNT(*) AS turns FROM usage_records"
    )
    fun observeTotals(): Flow<UsageTotals>

    @Query(
        "SELECT sessionId, providerId, modelId, " +
            "COALESCE(SUM(promptTokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completionTokens), 0) AS completionTokens, " +
            "COALESCE(SUM(CASE WHEN estimated THEN 1 ELSE 0 END), 0) AS estimatedTurns, " +
            "COUNT(*) AS turns FROM usage_records " +
            "GROUP BY sessionId, providerId, modelId " +
            "ORDER BY (promptTokens + completionTokens) DESC"
    )
    fun observeBySessionModel(): Flow<List<UsageBySessionModel>>
}

@Dao
interface CachedModelDao {
    @Query("SELECT * FROM cached_models WHERE providerId = :providerId ORDER BY modelId ASC")
    suspend fun getForProvider(providerId: String): List<CachedModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<CachedModel>)

    @Query("DELETE FROM cached_models WHERE providerId = :providerId")
    suspend fun clearForProvider(providerId: String)

    @androidx.room.Transaction
    suspend fun replaceForProvider(providerId: String, models: List<CachedModel>) {
        clearForProvider(providerId)
        insertAll(models)
    }
}

@Database(
    entities = [Session::class, Message::class, FavouriteModel::class, SessionSummary::class, CachedModel::class, UsageRecord::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun summaryDao(): SummaryDao
    abstract fun cachedModelDao(): CachedModelDao
    abstract fun usageDao(): UsageDao
}

/** v1 → v2: adds the cached_models table used by model fetching. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cached_models` (" +
                "`providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`providerId`, `modelId`))"
        )
    }
}

/**
 * v2 → v3 (Phase 5, Step 2): adds the usage_records table — one row per
 * model turn, so token totals are always derivable from stored rows.
 * No existing table is touched, so no user data moves or is lost.
 */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `usage_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, `providerId` TEXT NOT NULL, " +
                "`modelId` TEXT NOT NULL, `promptTokens` INTEGER NOT NULL, " +
                "`completionTokens` INTEGER NOT NULL, `estimated` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_usage_records_sessionId` " +
                "ON `usage_records` (`sessionId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_usage_records_providerId_modelId` " +
                "ON `usage_records` (`providerId`, `modelId`)"
        )
    }
}

/**
 * v3 → v4 (Phase 5, Step 4): adds the pinned column to sessions.
 * Existing rows get 0 (not pinned), so no session moves or is lost;
 * no other table is touched.
 */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `sessions` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * v4 → v5 (Phase 6, Step 2): adds the imagesJson column to messages —
 * a JSON array of attached-image file names (see ImageStore). Nullable
 * with no default, so existing messages simply have no images; no other
 * table is touched and no message text moves or is lost.
 */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `messages` ADD COLUMN `imagesJson` TEXT"
        )
    }
}
