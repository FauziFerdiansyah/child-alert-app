package com.childAlerts.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.VideoView
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TimerService : LifecycleService() {

    private var countDownTimer: CountDownTimer? = null
    private var overlayView: View? = null
    private var proximityOverlayView: View? = null
    private var batteryOverlayView: View? = null
    private var windowManager: WindowManager? = null
    
    private val CHANNEL_ID = "TimerServiceChannel"
    private val NOTIFICATION_ID = 1

    private val database = Firebase.database("https://childalert-d49c3-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val switchRef = database.getReference("switch/active")
    private val timerRef = database.getReference("timer")
    private val mediaRef = database.getReference("media")
    private val callRef = database.getReference("call")
    private val safetyRef = database.getReference("settings/faceSafety")
    private val lowBatteryRef = database.getReference("settings/lowBattery")

    private var isActive = false
    private var timerValue = 0L
    private var timerType = "minutes"
    private var mediaType = "image"
    private var faceSafetyEnabled = false
    private var lowBatteryEnabled = false

    private var isTimerPaused = false
    private var timeRemaining = 0L

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private val autoCloseRunnable = Runnable {
        if (overlayView != null) {
            removeOverlay()
        }
    }

    private val batteryAutoCloseRunnable = Runnable {
        // Setelah 3 menit, ubah enabled jadi false di Firebase (ini akan memicu removeBatteryOverlay via listener)
        lowBatteryRef.child("enabled").setValue(false)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        listenToFirebase()
    }

    private fun listenToFirebase() {
        switchRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newIsActive = snapshot.getValue(Boolean::class.java) ?: false
                if (isActive != newIsActive) {
                    isActive = newIsActive
                    resetTimer()
                    updateCameraState()
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
                val status = snapshot.child("status").getValue(String::class.java)

                if (type == "KDM" && status == "pending") {
                    callRef.child("status").setValue("played")
                    showOverlay(isKdmCall = true)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        safetyRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                faceSafetyEnabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                updateCameraState()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        lowBatteryRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newEnabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                if (newEnabled != lowBatteryEnabled) {
                    lowBatteryEnabled = newEnabled
                    if (lowBatteryEnabled) {
                        showBatteryOverlay()
                    } else {
                        removeBatteryOverlay()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateCameraState() {
        if (isActive && faceSafetyEnabled) {
            startCamera()
        } else {
            stopCamera()
            removeProximityOverlay()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
            )

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val face = faces[0]
                                val faceWidth = face.boundingBox.width()
                                if (faceWidth > 280) { 
                                    showProximityOverlay()
                                } else {
                                    removeProximityOverlay()
                                }
                            } else {
                                removeProximityOverlay()
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                Log.e("TimerService", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun resetTimer() {
        countDownTimer?.cancel()
        removeOverlay()
        isTimerPaused = false

        if (!isActive || timerValue <= 0) {
            updateNotification("Timer Inactive")
            timeRemaining = 0L
            return
        }

        timeRemaining = if (timerType == "minutes") {
            timerValue * 60 * 1000
        } else {
            timerValue * 60 * 60 * 1000
        }

        startCountdown(timeRemaining)
    }

    private fun pauseTimer() {
        if (!isTimerPaused && countDownTimer != null) {
            countDownTimer?.cancel()
            isTimerPaused = true
        }
    }

    private fun resumeTimer() {
        if (isTimerPaused && isActive && timeRemaining > 0 && 
            overlayView == null && proximityOverlayView == null && batteryOverlayView == null) {
            isTimerPaused = false
            startCountdown(timeRemaining)
        }
    }

    private fun startCountdown(durationMillis: Long) {
        countDownTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemaining = millisUntilFinished
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
                timeRemaining = 0L
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
            pauseTimer()
            videoView?.visibility = View.VISIBLE
            imageView?.visibility = View.GONE
            closeButton?.visibility = View.VISIBLE
            val videoPath = "android.resource://" + packageName + "/" + R.raw.kdm
            videoView?.setVideoURI(Uri.parse(videoPath))
            videoView?.setOnPreparedListener { mp ->
                mp.isLooping = false
                videoView.start()
            }
            videoView?.setOnCompletionListener {
                removeOverlay()
            }
        } else if (mediaType == "video") {
            videoView?.visibility = View.VISIBLE
            imageView?.visibility = View.GONE
            closeButton?.visibility = View.VISIBLE
            val videoPath = "android.resource://" + packageName + "/" + R.raw.video_suzzana
            videoView?.setVideoURI(Uri.parse(videoPath))
            videoView?.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoView.start()
            }
            overlayView?.postDelayed(autoCloseRunnable, 60000)
        } else {
            imageView?.visibility = View.VISIBLE
            videoView?.visibility = View.GONE
            closeButton?.visibility = View.VISIBLE
            imageView?.setImageResource(R.drawable.suzzana)
            overlayView?.postDelayed(autoCloseRunnable, 60000)
        }
        
        closeButton?.setOnClickListener {
            removeOverlay()
            if (!isKdmCall && isActive && timerValue > 0) {
                resetTimer()
            }
        }

        windowManager?.addView(overlayView, params)
    }

    private fun showProximityOverlay() {
        if (proximityOverlayView != null) return

        pauseTimer()

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

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        proximityOverlayView = inflater.inflate(R.layout.overlay_layout, null)
        
        val videoView = proximityOverlayView?.findViewById<VideoView>(R.id.overlay_video)
        val imageView = proximityOverlayView?.findViewById<ImageView>(R.id.overlay_image)
        val closeButton = proximityOverlayView?.findViewById<ImageButton>(R.id.close_button)
        
        imageView?.visibility = View.GONE
        closeButton?.visibility = View.GONE
        videoView?.visibility = View.VISIBLE
        
        val videoPath = "android.resource://" + packageName + "/" + R.raw.warning_face
        videoView?.setVideoURI(Uri.parse(videoPath))
        videoView?.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoView.start()
        }
        videoView?.setOnCompletionListener {
            removeProximityOverlay()
        }

        windowManager?.addView(proximityOverlayView, params)
    }

    private fun showBatteryOverlay() {
        if (batteryOverlayView != null) return

        pauseTimer()

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

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        batteryOverlayView = inflater.inflate(R.layout.overlay_layout, null)
        
        val videoView = batteryOverlayView?.findViewById<VideoView>(R.id.overlay_video)
        val imageView = batteryOverlayView?.findViewById<ImageView>(R.id.overlay_image)
        val closeButton = batteryOverlayView?.findViewById<ImageButton>(R.id.close_button)
        
        imageView?.visibility = View.GONE
        closeButton?.visibility = View.GONE
        videoView?.visibility = View.VISIBLE
        
        val videoPath = "android.resource://" + packageName + "/" + R.raw.low_battery
        videoView?.setVideoURI(Uri.parse(videoPath))
        videoView?.setOnPreparedListener { mp ->
            mp.isLooping = true
            videoView.start()
        }

        windowManager?.addView(batteryOverlayView, params)
        
        // Auto close after 3 minutes (180,000 ms)
        batteryOverlayView?.postDelayed(batteryAutoCloseRunnable, 180000)
    }

    private fun removeBatteryOverlay() {
        batteryOverlayView?.let {
            val videoView = it.findViewById<VideoView>(R.id.overlay_video)
            videoView?.stopPlayback()
            it.removeCallbacks(batteryAutoCloseRunnable)
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            batteryOverlayView = null
            resumeTimer()
        }
    }

    private fun removeProximityOverlay() {
        proximityOverlayView?.let {
            val videoView = it.findViewById<VideoView>(R.id.overlay_video)
            videoView?.stopPlayback()
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            proximityOverlayView = null
            resumeTimer()
        }
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
            resumeTimer()
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
        super.onStartCommand(intent, flags, startId)
        updateNotification("Timer Service Running")
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        removeOverlay()
        removeProximityOverlay()
        removeBatteryOverlay()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
