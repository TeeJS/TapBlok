package com.cj.tapblok.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    /** REPLACE, not IGNORE: counters are written far more often than they are created. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: ScopeUsage)

    @Query("SELECT * FROM usage WHERE scopeId = :scopeId")
    suspend fun get(scopeId: String): ScopeUsage?

    @Query("SELECT * FROM usage")
    suspend fun getAll(): List<ScopeUsage>

    @Query("SELECT * FROM usage")
    fun observeAll(): Flow<List<ScopeUsage>>

    @Query("DELETE FROM usage WHERE scopeId = :scopeId")
    suspend fun delete(scopeId: String)

    @Query("DELETE FROM usage")
    suspend fun deleteAll()
}
