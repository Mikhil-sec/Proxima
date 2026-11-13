package mu.proxima.mesh

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** One row per packetId this node has already handled (dedup survives an app restart). */
@Entity(tableName = "seen_packets")
data class SeenPacket(
    @PrimaryKey val packetId: String,
    val firstSeenMs: Long,
)

@Dao
interface SeenPacketDao {
    /** Returns -1 when the id already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SeenPacket): Long

    @Query("DELETE FROM seen_packets WHERE firstSeenMs < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long): Int
}

@Database(entities = [SeenPacket::class], version = 1, exportSchema = false)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun seen(): SeenPacketDao

    companion object {
        fun create(context: Context): MeshDatabase =
            Room.databaseBuilder(context.applicationContext, MeshDatabase::class.java, "proxima-mesh.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/**
 * Dedup by packetId: an in-memory set in front of Room, so the hot path never waits on disk
 * twice for the same id.
 */
class SeenStore(private val dao: SeenPacketDao, private val clock: () -> Long = System::currentTimeMillis) {
    private val recent = LinkedHashSet<String>()

    /** True the first time an id is seen, false for every duplicate. */
    suspend fun markFirstSeen(packetId: String): Boolean {
        synchronized(recent) {
            if (packetId in recent) return false
            recent += packetId
            if (recent.size > MAX_RECENT) recent.remove(recent.first())
        }
        return dao.insert(SeenPacket(packetId, clock())) != -1L
    }

    suspend fun prune(maxAgeMs: Long = 2 * 60 * 60 * 1000L) {
        dao.pruneOlderThan(clock() - maxAgeMs)
    }

    private companion object { const val MAX_RECENT = 4_096 }
}
