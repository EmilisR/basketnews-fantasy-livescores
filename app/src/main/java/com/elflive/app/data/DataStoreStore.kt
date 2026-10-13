package com.elflive.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elflive.app.core.Persisted
import com.elflive.app.core.StateStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "elf_state")

/** Teams, cached schedule, cached final box scores and the last computed state, as one JSON blob. */
class DataStoreStore(private val context: Context) : StateStore {
    private val key = stringPreferencesKey("persisted")

    override suspend fun load(): Persisted {
        val raw = context.dataStore.data.first()[key] ?: return Persisted()
        return runCatching { JSON.decodeFromString(Persisted.serializer(), raw) }.getOrElse { Persisted() }
    }

    override suspend fun save(p: Persisted) {
        val raw = JSON.encodeToString(Persisted.serializer(), p)
        context.dataStore.edit { it[key] = raw }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
