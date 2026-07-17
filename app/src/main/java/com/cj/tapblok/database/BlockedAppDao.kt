package com.cj.tapblok.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blockedApp: BlockedApp)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(blockedApps: List<BlockedApp>)

    /** Used by the override editor; IGNORE-on-insert would silently drop rule edits. */
    @Update
    suspend fun update(blockedApp: BlockedApp)

    @Query("DELETE FROM blocked_apps")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(blockedApp: BlockedApp)

    @Query("SELECT * FROM blocked_apps")
    fun getAllBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps")
    suspend fun getAllBlockedAppsList(): List<BlockedApp>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): BlockedApp?

    @Query("SELECT * FROM blocked_apps WHERE groupId = :groupId")
    suspend fun getByGroup(groupId: String): List<BlockedApp>
}
