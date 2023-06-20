package com.programmersbox.common.database

import androidx.compose.runtime.staticCompositionLocalOf
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.asFlow
import io.realm.kotlin.migration.AutomaticSchemaMigration
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.flow.mapNotNull

internal val LocalDatabase = staticCompositionLocalOf<GameBoyDatabase> { error("Nothing here!") }

internal class GameBoyDatabase(name: String = Realm.DEFAULT_FILE_NAME) {
    private val realm by lazy {
        Realm.open(
            RealmConfiguration.Builder(
                setOf(
                    GameBoySettings::class
                )
            )
                .schemaVersion(23)
                .name(name)
                .migration(AutomaticSchemaMigration { })
                .deleteRealmIfMigrationNeeded()
                .build()
        )
    }

    private val settings = realm.initDbBlocking { GameBoySettings() }

    fun getSettings() = settings
        .asFlow()
        .mapNotNull { it.obj }

    suspend fun romLocation(location: String?) = realm.updateInfo<GameBoySettings> { it?.lastRomLocation = location }
}

private suspend inline fun <reified T : RealmObject> Realm.updateInfo(crossinline block: MutableRealm.(T?) -> Unit) {
    query(T::class).first().find()?.also { info ->
        write { block(findLatest(info)) }
    }
}

private inline fun <reified T : RealmObject> Realm.initDbBlocking(crossinline default: () -> T): T {
    val f = query(T::class).first().find()
    return f ?: writeBlocking { copyToRealm(default()) }
}