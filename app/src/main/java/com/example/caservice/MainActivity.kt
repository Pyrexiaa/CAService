package com.example.caservice

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.widget.Toast
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.caservice.databinding.ActivityMainBinding

class ControllerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding


    companion object {
        const val MSG_GET_SENSOR = 1
        const val MSG_SENSOR_RESPONSE = 2
    }

    private var serviceMessenger: Messenger? = null
    @SuppressLint("SetTextI18n")
    private val clientMessenger = Messenger(Handler(Looper.getMainLooper()) {
        if (it.what == MSG_SENSOR_RESPONSE) {
            val status = it.data.getString("sensor_status")
            val sensorScore = it.data.getInt("sensor_score")
            binding.sensorStatus.text = "Status: ${status ?: "Unknown"}"
            binding.sensorScore.text = "Score: $sensorScore"
            true
        } else false
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
        }
    }

    private fun bindService() {
        val intent = Intent()
        intent.setClassName("com.example.serviceapp", "com.example.serviceapp.service.SmartService")
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    private fun requestGaitStatus() {
        val msg = Message.obtain(null, MSG_GET_SENSOR)
        msg.replyTo = clientMessenger
        serviceMessenger?.send(msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
        // Bind to service
        bindService()

        // Button to request gait status
        binding.btnRequestGait.setOnClickListener {
            requestGaitStatus()
        }
    }
}