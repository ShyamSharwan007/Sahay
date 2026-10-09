package com.sahay.engine.pack

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sahay.core.contracts.ShelterStatus

/** Latest known status of one shelter, learned from signed shelter-status messages. */
@Entity(tableName = "shelter_status")
data class ShelterStatusEntity(
    @PrimaryKey val shelterId: String,
    val status: String,
    val updatedAt: Long,
)

@Dao
interface ShelterStatusDao {
    /** Insert, or overwrite only when the new message is not older than what we have ("keep newest"). */
    @Query(
        "INSERT INTO shelter_status (shelterId, status, updatedAt) VALUES (:shelterId, :status, :updatedAt) " +
            "ON CONFLICT(shelterId) DO UPDATE SET status = excluded.status, updatedAt = excluded.updatedAt " +
            "WHERE excluded.updatedAt >= shelter_status.updatedAt",
    )
    suspend fun upsertIfNewer(shelterId: String, status: String, updatedAt: Long)

    @Query("SELECT * FROM shelter_status")
    suspend fun all(): List<ShelterStatusEntity>

    @Query("DELETE FROM shelter_status")
    suspend fun clear()
}

@Database(entities = [ShelterStatusEntity::class], version = 1, exportSchema = false)
abstract class ShelterStatusDatabase : RoomDatabase() {
    abstract fun dao(): ShelterStatusDao
}

/** Small writable store next to the read-only pack (the pack file itself is never modified). */
internal class ShelterStatusStore(private val dao: ShelterStatusDao) {

    constructor(context: Context) : this(
        Room.databaseBuilder(context.applicationContext, ShelterStatusDatabase::class.java, DB_NAME).build().dao(),
    )

    suspend fun update(shelterId: String, status: ShelterStatus, updatedAtEpochSec: Long) =
        dao.upsertIfNewer(shelterId, status.name, updatedAtEpochSec)

    /** Unknown status names (written by a newer app version) are ignored. */
    suspend fun all(): Map<String, ShelterStatus> =
        dao.all().mapNotNull { row ->
            ShelterStatus.entries.firstOrNull { it.name == row.status }?.let { row.shelterId to it }
        }.toMap()

    suspend fun clear() = dao.clear()

    private companion object {
        const val DB_NAME = "sahay_shelter_status.db"
    }
}
