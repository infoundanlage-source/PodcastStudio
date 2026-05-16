package com.timerflow.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import com.timerflow.service.TimerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RunViewModel(app: Application) : AndroidViewModel(app) {

    private var service: TimerService? = null
    private val _serviceState = MutableStateFlow(TimerService.TimerState())
    val serviceState: StateFlow<TimerService.TimerState> = _serviceState.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as TimerService.TimerBinder).getService()
            service = s
            // Mirror service state into our flow
            _serviceState.value = s.state.value
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bind(context: Context) {
        val intent = Intent(context, TimerService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind(context: Context) {
        runCatching { context.unbindService(connection) }
        service = null
    }

    fun getService(): TimerService? = service

    override fun onCleared() {
        super.onCleared()
        service = null
    }
}
