package com.sahay.comms.reports

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

/** Where a report is on its way to the server. */
enum class ReportStatus {
    /** Saved on the phone, not delivered yet. Retried when the connection returns. */
    PENDING,
    SENT,
    /** The server refused it for good (for example a location outside every region). Never retried. */
    FAILED,
}

/**
 * A hazard report made on this phone. [localId] never changes; [serverId] and the server's trust are filled in once
 * the server has accepted it. The reporter's own position is kept for the trust score (proximity).
 */
@Entity(tableName = "reports", indices = [Index("status"), Index("createdAtEpochSec")])
data class ReportEntity(
    @PrimaryKey val localId: String,
    val serverId: String?,
    val type: String,                 // HazardType.code
    val lat: Double,
    val lon: Double,
    val reporterLat: Double?,
    val reporterLon: Double?,
    val note: String?,
    val photoPath: String?,
    val createdAtEpochSec: Long,
    val status: String,               // ReportStatus.name
    val channel: String,              // Channel.name
    val serverTrust: Double?,
    val serverPhotoUrl: String?,
)

@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: ReportEntity)

    @Query("SELECT * FROM reports ORDER BY createdAtEpochSec DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("SELECT COUNT(*) FROM reports WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM reports WHERE localId = :localId")
    suspend fun find(localId: String): ReportEntity?

    @Query("SELECT * FROM reports WHERE status = 'PENDING' ORDER BY createdAtEpochSec ASC")
    suspend fun pending(): List<ReportEntity>

    @Query("UPDATE reports SET status = 'SENT', serverId = :serverId, serverTrust = :trust, serverPhotoUrl = :photoUrl WHERE localId = :localId")
    suspend fun markSent(localId: String, serverId: String, trust: Double, photoUrl: String?)

    @Query("UPDATE reports SET status = 'FAILED' WHERE localId = :localId")
    suspend fun markFailed(localId: String)

    /** Finished reports older than [olderThanEpochSec] are of no use any more. Pending ones are always kept. */
    @Query("DELETE FROM reports WHERE status != 'PENDING' AND createdAtEpochSec < :olderThanEpochSec")
    suspend fun deleteFinishedBefore(olderThanEpochSec: Long)
}

/**
 * Own database file, and deliberately no destructive-migration fallback: a queued report is the user's data and
 * must survive an app update (unlike alerts, it cannot be fetched again). Schema changes need a real Migration.
 */
@Database(entities = [ReportEntity::class], version = 1, exportSchema = false)
abstract class ReportDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao
}
