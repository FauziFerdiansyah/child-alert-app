package com.childAlerts.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.VideoView
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class TimerService : Service() {

    private var countDownTimer: CountDownTimer? = null
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    
    private val CHANNEL_ID = "TimerServiceChannel"
    private val NOTIFICATION_ID = 1

    private val database = Firebase.database("https://childalert-d49c3-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val switchRef = database.getReference("switch/active")
    private val timerRef = database.getReference("timer")
    private val mediaRef = database.getReference("media")
    private val callRef = database.getReference("call")

    private var isActive = false
    private var timerValue = 0L
    private var timerType = "minutes"
    private var mediaType = "image"
    private var lastCallTimestamp = 0L

    private val autoCloseRunnable = Runnable {
        if (overlayView != null) {
            removeOverlay()
            if (isActive && timerValue > 0) {
                resetTimer()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        listenToFirebase()
    }

    private fun listenToFirebase() {
        switchRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newIsActive = snapshot.getValue(Boolean::class.java) ?: false
                if (isActive != newIsActive) {
                    isActive = newIsActive
                    resetTimer()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        timerRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newValue = snapshot.child("value").getValue(Long::class.java) ?: 0L
                val newType = snapshot.child("type").getValue(String::class.java) ?: "minutes"
                
                if (timerValue != newValue || timerType != newType) {
                    timerValue = newValue
                    timerType = newType
                    resetTimer()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        mediaRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                mediaType = snapshot.child("type").getValue(String::class.java) ?: "image"
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val type = snapshot.child("type").getValue(String::class.java)
                val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                val status = snapshot.child("status").getValue(String::class.java)

                if (type == "KDM" && status == "pending" && timestamp > lastCallTimestamp) {
                    lastCallTimestamp = timestamp
                    showOverlay(isKdmCall = true)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun resetTimer() {
        countDownTimer?.cancel()
        removeOverlay()

        if (!isActive || timerValue <= 0) {
            updateNotification("Timer Inactive")
            return
        }

        val durationMillis = if (timerType == "minutes") {
            timerValue * 60 * 1000
        } else {
            timerValue * 60 * 60 * 1000
        }

        startCountdown(durationMillis)
    }

    private fun startCountdown(durationMillis: Long) {
        countDownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) % 60
                val minutes = (millisUntilFinished / (1000 * 60)) % 60
                val hours = (millisUntilFinished / (1000 * 60 * 60))
                
                val timeString = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }
                updateNotification("Countdown: $timeString")
            }

            override fun onFinish() {
                updateNotification("Alert Triggered!")
                showOverlay()
            }
        }.start()
    }

    private fun showOverlay(isKdmCall: Boolean = false) {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        
        val imageView = overlayView?.findViewById<ImageView>(R.id.overlay_image)
        val videoView = overlayView?.findViewById<VideoView>(R.id.overlay_video)
        val closeButton = overlayView?.findViewById<ImageButton>(R.id.close_button)

        if (isKdmCall) {
            videoView?.visibility = View.VISIBLE
            imageView?.visibility = View.GONE
            val videoPath = "android.resource://" + packageName + "/" + R.raw.kdm
            videoView?.setVideoURI(Uri.parse(videoPath))
            videoView?.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoView.start()
            }
        } else if (mediaType == "video") {
            videoView?.visibility = View.VISIBLE
            imageView?.visibility = View.GONE
            val videoPath = "android.resource://" + packageName + "/" + R.raw.video_suzzana
            videoView?.setVideoURI(Uri.parse(videoPath))
            videoView?.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoView.start()
            }
        } else {
            imageView?.visibility = View.VISIBLE
            videoView?.visibility = View.GONE
            imageView?.setImageResource(R.drawable.suzzana)
        }
        
        closeButton?.setOnClickListener {
            removeOverlay()
            if (isActive && timerValue > 0) {
                resetTimer()
            }
        }

        windowManager?.addView(overlayView, params)

        // Auto close after 1 minute and restart timer
        overlayView?.postDelayed(autoCloseRunnable, 60000)
    }

    private fun removeOverlay() {
        overlayView?.let {
            val videoView = it.findViewById<VideoView>(R.id.overlay_video)
            videoView?.stopPlayback()
            it.removeCallbacks(autoCloseRunnable)
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Timer Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateNotification(contentText: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Child Alert Timer")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.duck)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification("Timer Service Running")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        countDownTimer?.cancel()
        removeOverlay()
        super.onDestroy()
    }
}
