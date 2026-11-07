package dev.davidx3d.wisp.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val provider: String,
    val title: String,
    val durationSeconds: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user", "assistant" or "tool". */
    val role: String,
    val text: String,
    val timestamp: Long,
    val isError: Boolean = false,
)

data class ConversationSummary(
    val id: Long,
    val startedAt: Long,
    val provider: String,
    val title: String,
    val durationSeconds: Long,
    val messageCount: Int,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertConversation(c: ConversationEntity): Long

    @Insert
    suspend fun insertMessages(m: List<MessageEntity>)

    @Query(
        """SELECT c.id, c.startedAt, c.provider, c.title, c.durationSeconds,
           (SELECT COUNT(*) FROM messages m WHERE m.conversationId = c.id) AS messageCount
           FROM conversations c
           WHERE :q = '' OR c.title LIKE '%' || :q || '%'
              OR EXISTS (SELECT 1 FROM messages m WHERE m.conversationId = c.id AND m.text LIKE '%' || :q || '%')
           ORDER BY c.startedAt DESC""",
    )
    fun search(q: String): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun conversation(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY timestamp, id")
    fun messages(id: Long): Flow<List<MessageEntity>>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Transaction
    suspend fun save(c: ConversationEntity, messages: List<MessageEntity>) {
        val id = insertConversation(c)
        insertMessages(messages.map { it.copy(conversationId = id) })
    }
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class WispDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext context: Context): WispDatabase =
        Room.databaseBuilder(context, WispDatabase::class.java, "wisp.db").build()

    @Provides
    fun historyDao(db: WispDatabase): HistoryDao = db.history()
}

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {
    fun search(query: String) = dao.search(query.trim())
    fun conversation(id: Long) = dao.conversation(id)
    fun messages(id: Long) = dao.messages(id)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()
    suspend fun save(c: ConversationEntity, messages: List<MessageEntity>) {
        if (messages.none { it.role != "tool" }) return
        dao.save(c, messages)
    }
}
