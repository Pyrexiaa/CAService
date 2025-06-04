package com.example.serviceapp.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

class SmartService : Service() {

    companion object {
        const val MSG_GET_GAIT = 1
        const val MSG_GAIT_RESPONSE = 2
    }

    private val messenger = Messenger(IncomingHandler())

    @SuppressLint("HandlerLeak")
    inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_GET_GAIT -> {
                    val replyMessenger = msg.replyTo
                    val response = Message.obtain(null, MSG_GAIT_RESPONSE)
                    val bundle = Bundle()
                    bundle.putString("gait_status", "Stable")
                    response.data = bundle
                    replyMessenger.send(response)
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return messenger.binder
    }
}