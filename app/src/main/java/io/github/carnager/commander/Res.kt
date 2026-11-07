package io.github.carnager.commander

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** String resources for code without a Compose context (view model, transfers, file systems). */
object Res {
    lateinit var app: Context

    fun str(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        app.resources.getQuantityString(id, count, *args)
}
