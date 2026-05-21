package com.sorrowblue.kioarch

import android.content.Context
import android.util.Log
import androidx.startup.Initializer

class KioArchInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        KioArch.loadLibrary()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
