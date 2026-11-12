package com.inyogeshwar.codesathi.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
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
    val status: String = "active"
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
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): Session?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: Session)

    @Query("UPDATE sessions SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE sessions SET agentMode = :mode WHERE id = :id")
    suspend fun setMode(id: String, mode: String)

    @Query("UPDATE sessions SET providerId = :providerId, modelId = :modelId WHERE id = :id")
    suspend fun setModel(id: String, providerId: String, modelId: String)

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
    entities = [Session::class, Message::class, FavouriteModel::class, SessionSummary::class, CachedModel::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun summaryDao(): SummaryDao
    abstract fun cachedModelDao(): CachedModelDao
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
