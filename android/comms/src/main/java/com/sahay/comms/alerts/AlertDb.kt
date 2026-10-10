package com.sahay.comms.alerts

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One alert, whatever channel it came by. [dedupeKey] is the SHA-256 of the signed wire or of the SMS body,
 * so the same alert arriving by internet and by SMS is stored once.
 */
@Entity(tableName = "alerts", indices = [Index("alertId"), Index("issuedAtEpochSec")])
data class AlertEntity(
    @PrimaryKey val dedupeKey: String,
    val alertId: String,
    val templateCode: String?,
    val severity: Int,
    val lat: Double?,
    val lon: Double?,
    val radiusM: Int?,
    val issuedAtEpochSec: Long,
    val isSimulation: Boolean,
    val title: String,
    val body: String,
    val titleEn: String,
    val bodyEn: String,
    val originalText: String?,
    val source: String,
    val verification: String,
    val receivedAtEpochSec: Long,
    val read: Boolean,
    /** Region the alert was issued for; null for alerts that are not tied to one (official SMS, pasted text). */
    val regionId: String? = null,
    /** Null = no expiry known, see [AlertScope.DEFAULT_LIFETIME_SEC]. */
    val expiresAtEpochSec: Long? = null,
)

@Dao
interface AlertDao {
    /** Returns the new row id, or -1 if an alert with this key already exists (first copy wins). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(alert: AlertEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY issuedAtEpochSec DESC, receivedAtEpochSec DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE `read` = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE alerts SET `read` = 1 WHERE alertId = :alertId")
    suspend fun markRead(alertId: String)

    /** Ids of regional alerts that do not belong to [keepRegionId] (null = keep none). */
    @Query("SELECT alertId FROM alerts WHERE regionId IS NOT NULL AND (:keepRegionId IS NULL OR regionId != :keepRegionId)")
    suspend fun regionalAlertIdsOutside(keepRegionId: String?): List<String>

    @Query("DELETE FROM alerts WHERE regionId IS NOT NULL AND (:keepRegionId IS NULL OR regionId != :keepRegionId)")
    suspend fun deleteRegionalOutside(keepRegionId: String?)

    @Query("SELECT EXISTS(SELECT 1 FROM alerts WHERE dedupeKey = :dedupeKey)")
    suspend fun exists(dedupeKey: String): Boolean

    /** A null text keeps the stored one. */
    @Query("UPDATE alerts SET body = COALESCE(:body, body), bodyEn = COALESCE(:bodyEn, bodyEn) WHERE dedupeKey = :dedupeKey")
    suspend fun updateTexts(dedupeKey: String, body: String?, bodyEn: String?)
}

/** `GET /alert-templates` answers, kept for when there is no trip pack and no internet. */
@Entity(tableName = "template_cache", primaryKeys = ["code", "lang"])
data class TemplateEntity(
    val code: String,
    val lang: String,
    val severity: Int,
    val title: String,
    val body: String,
)

@Dao
interface TemplateCacheDao {
    @Query("SELECT * FROM template_cache WHERE code = :code AND lang = :lang")
    suspend fun find(code: String, lang: String): TemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(templates: List<TemplateEntity>)
}

@Database(entities = [AlertEntity::class, TemplateEntity::class], version = 2, exportSchema = false)
abstract class CommsDatabase : RoomDatabase() {
    abstract fun alertDao(): AlertDao
    abstract fun templateCacheDao(): TemplateCacheDao
}
