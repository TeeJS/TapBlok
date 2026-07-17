package com.cj.tapblok.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Nothing populates groups until the v2 UI lands; the rule resolution and usage keying that
 * consume them are already in place.
 */
@Dao
interface AppGroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: AppGroup)

    @Update
    suspend fun update(group: AppGroup)

    @Delete
    suspend fun delete(group: AppGroup)

    @Query("SELECT * FROM app_groups WHERE groupId = :groupId")
    suspend fun get(groupId: String): AppGroup?

    @Query("SELECT * FROM app_groups")
    suspend fun getAll(): List<AppGroup>

    @Query("SELECT * FROM app_groups")
    fun observeAll(): Flow<List<AppGroup>>
}
