package com.cj.tapblok.database

import androidx.room.TypeConverter

/**
 * Stores [TagUnlockMode] as its name rather than its ordinal. Explicit on purpose: an
 * ordinal would silently rebind every stored row if the enum constants were ever reordered.
 */
class Converters {

    @TypeConverter
    fun toTagUnlockMode(value: String?): TagUnlockMode? = value?.let { TagUnlockMode.valueOf(it) }

    @TypeConverter
    fun fromTagUnlockMode(mode: TagUnlockMode?): String? = mode?.name
}
