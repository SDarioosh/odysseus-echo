package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: Android's alarm clock fired (AlarmClock.scheduleBackstop, the backstop for the
// next ring). Starts the alarm clock if the process was stopped, and makes it look now; the ring
// itself is decided by AlarmClock as always. Declared in AndroidManifest.xml (not exported).

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.device.DeviceManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

class AlarmClockReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun deviceManager(): DeviceManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("Alarm clock: Android's alarm clock fired")
        val dm = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).deviceManager()
        AlarmClock.start(context, dm.config)
        AlarmClock.poke()
    }
}
