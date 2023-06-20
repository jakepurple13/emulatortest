package com.programmersbox.common.database

import io.realm.kotlin.types.RealmObject

internal class GameBoySettings : RealmObject {
    var lastRomLocation: String? = ""
}
