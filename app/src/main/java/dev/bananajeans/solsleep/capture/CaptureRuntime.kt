// SPDX-License-Identifier: GPL-3.0-or-later
package dev.bananajeans.solsleep.capture

import android.content.Context
import android.provider.Settings
import android.os.Handler
import android.os.HandlerThread
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CaptureRuntime {
    val ownerId: UUID=UUID.randomUUID()
    @Volatile var liveOwner: UUID?=null
        private set
    // One process-wide actor also orders destruction of an old Service before a new acquisition.
    val handler: Handler by lazy {Handler(HandlerThread("solsleep-motion-capture").apply {start()}.looper)}
    @Synchronized fun opened(owner: UUID) {liveOwner=owner}
    @Synchronized fun closed(owner: UUID) {if(liveOwner==owner) liveOwner=null}
    private val mutableError=MutableStateFlow(false)
    val error=mutableError.asStateFlow()
    fun failed() {mutableError.value=true}
    fun clearError() {mutableError.value=false}
    /** Random per-boot identity, no stable device identifier. Unavailable boot count => process scope. */
    @Synchronized fun bootId(context: Context): UUID {
        val count=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
        if(count<0) return ownerId
        val preferences=context.getSharedPreferences("capture-boot",Context.MODE_PRIVATE)
        val saved=preferences.getString("id",null)
        if(preferences.getInt("count",-1)==count && saved!=null) return UUID.fromString(saved)
        val id=UUID.randomUUID()
        check(preferences.edit().putInt("count",count).putString("id",id.toString()).commit())
        return id
    }
}
