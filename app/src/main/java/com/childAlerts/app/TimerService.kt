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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
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
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TimerService : LifecycleService() {

    private var countDownTimer: CountDownTimer? = null
    private var overlayView: View? = null
    private var proximityOverlayView: View? = null
    private var batteryOverlayView: View? = null
    private var windowManager: WindowManager? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "TimerServiceChannel"
    private val NOTIFICATION_ID = 1

    private val database = Firebase.database("https://childalert-d49c3-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val switchRef = database.getReference("switch/active")
    private val timerRef = database.getReference("timer")
    private val mediaRef = database.getReference("media")
    private val callRef = database.getReference("call")
    private val safetyRef = database.getReference("settings/faceSafety")
    private val lowBatteryRef = database.getReference("settings/lowBattery")
    private val typeFaceRef = database.getReference("type_face")

    private var isActive = false
    private var timerValue = 0L
    private var timerType = "minutes"
    private var mediaType = "image"
    private var faceSafetyEnabled = false
    private var lowBatteryEnabled = false
    private var typeFace = "ghost"

    private var isTimerPaused = false
    private var timeRemaining = 0L
    private var isCameraStarted = false
    private var isProximityActive = false
    
    // Interval 200ms (5 FPS) agar responsif tapi tidak panas
    private var lastAnalysisTime = 0L
    private val analysisInterval = 200L 
    
    private var faceLostCount = 0
    private val MAX_FACE_LOST = 2 

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private val autoCloseRunnable = Runnable { removeOverlay() }
    private val batteryAutoCloseRunnable = Runnable {
        lowBatteryRef.child("enabled").setValue(false)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraExecutor = Executors.newSingleThreadExecutor()
        updateNotification("Child Alert Service is Running")
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
                val status = snapshot.child("status").getValue(String::class.java)
                if (type == "KDM" && status == "pending") {
                    callRef.child("status").setValue("played")
                    mainHandler.post { showOverlay(isKdmCall = true) }
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
                    mainHandler.post {
                        if (lowBatteryEnabled) showBatteryOverlay() else removeBatteryOverlay()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        typeFaceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newType = snapshot.getValue(String::class.java) ?: "ghost"
                if (typeFace != newType) {
                    typeFace = newType
                    if (proximityOverlayView != null) {
                        mainHandler.post { removeProximityOverlay() }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun updateCameraState() {
        mainHandler.post {
            if (faceSafetyEnabled) {
                if (!isCameraStarted) startCamera()
            } else {
                stopCamera()
                removeProximityOverlay()
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        
        isCameraStarted = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val detector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .build()
                )

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAnalysisTime < analysisInterval) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastAnalysisTime = currentTime

                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val image = InputImage.fromMediaImage(mediaImage, rotation)
                        detector.process(image)
                            .addOnSuccessListener { faces ->
                                val face = faces.firstOrNull()
                                if (face != null) {
                                    faceLostCount = 0 
                                    
                                    val faceWidth = face.boundingBox.width()
                                    val imgWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                                    val faceRatio = faceWidth.toFloat() / imgWidth
                                    
                                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
                                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
                                    var eyeRatio = 0f
                                    if (leftEye != null && rightEye != null) {
                                        val dist = Math.hypot((leftEye.position.x - rightEye.position.x).toDouble(), 
                                                              (leftEye.position.y - rightEye.position.y).toDouble()).toFloat()
                                        eyeRatio = dist / imgWidth
                                    }

                                    // Threshold yang lebih realistis agar ML Kit tidak 'blind' (stuck)
                                    if (faceRatio > 0.85f || eyeRatio > 0.38f) {
                                        if (!isProximityActive) {
                                            isProximityActive = true
                                            mainHandler.post { showProximityOverlay() }
                                        }
                                    } else if (faceRatio < 0.70f && eyeRatio < 0.30f) {
                                        if (isProximityActive) {
                                            isProximityActive = false
                                            mainHandler.post { removeProximityOverlay() }
                                        }
                                    }
                                } else {
                                    if (isProximityActive) {
                                        faceLostCount++
                                        if (faceLostCount >= MAX_FACE_LOST) {
                                            isProximityActive = false
                                            mainHandler.post { removeProximityOverlay() }
                                        }
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                isCameraStarted = false
                Log.e("TimerService", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        isCameraStarted = false
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
        timeRemaining = if (timerType == "minutes") timerValue * 60000 else timerValue * 3600000
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
                val minutes = (millisUntilFinished / 60000) % 60
                val hours = (millisUntilFinished / 3600000)
                val timeString = if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
                                 else String.format("%02d:%02d", minutes, seconds)
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
        val params = getOverlayParams()
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
            playVideo(videoView, R.raw.kdm, false) { removeOverlay() }
        } else if (mediaType == "video") {
            videoView?.visibility = View.VISIBLE
            imageView?.visibility = View.GONE
            closeButton?.visibility = View.VISIBLE
            playVideo(videoView, R.raw.video_suzzana, true, null)
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
            if (!isKdmCall && isActive && timerValue > 0) resetTimer()
        }
        addViewSafely(overlayView, params)
    }

    private fun showProximityOverlay() {
        if (proximityOverlayView != null || batteryOverlayView != null || overlayView != null) return

        pauseTimer()
        val params = getOverlayParams()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        proximityOverlayView = inflater.inflate(R.layout.overlay_layout, null)
        
        val videoView = proximityOverlayView?.findViewById<VideoView>(R.id.overlay_video)
        val imageView = proximityOverlayView?.findViewById<ImageView>(R.id.overlay_image)
        val closeButton = proximityOverlayView?.findViewById<ImageButton>(R.id.close_button)
        
        imageView?.visibility = View.GONE
        closeButton?.visibility = View.GONE
        
        if (typeFace == "blank") {
            videoView?.visibility = View.GONE
        } else {
            videoView?.visibility = View.VISIBLE
            val resId = if (typeFace == "monkey") R.raw.monkey_face else R.raw.warning_face
            playVideo(videoView, resId, true, null)
        }
        addViewSafely(proximityOverlayView, params)
    }

    private fun showBatteryOverlay() {
        if (batteryOverlayView != null) return
        pauseTimer()
        val params = getOverlayParams()
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        batteryOverlayView = inflater.inflate(R.layout.overlay_layout, null)
        
        val videoView = batteryOverlayView?.findViewById<VideoView>(R.id.overlay_video)
        val imageView = batteryOverlayView?.findViewById<ImageView>(R.id.overlay_image)
        val closeButton = batteryOverlayView?.findViewById<ImageButton>(R.id.close_button)
        
        imageView?.visibility = View.GONE
        closeButton?.visibility = View.GONE
        videoView?.visibility = View.VISIBLE
        playVideo(videoView, R.raw.low_battery, true, null)
        
        addViewSafely(batteryOverlayView, params)
        batteryOverlayView?.postDelayed(batteryAutoCloseRunnable, 180000)
    }

    private fun playVideo(videoView: VideoView?, resId: Int, looping: Boolean, onComplete: (() -> Unit)?) {
        try {
            videoView?.apply {
                setVideoURI(Uri.parse("android.resource://$packageName/$resId"))
                setOnPreparedListener { it.isLooping = looping; it.setVolume(1f, 1f); start() }
                setOnCompletionListener { onComplete?.invoke() }
                setOnErrorListener { _, _, _ -> true }
            }
        } catch (e: Exception) { Log.e("TimerService", "Video play failed", e) }
    }

    private fun getOverlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    private fun addViewSafely(view: View?, params: WindowManager.LayoutParams) {
        mainHandler.post {
            try { 
                if (view != null && view.parent == null) windowManager?.addView(view, params)
            } catch (e: Exception) { Log.e("TimerService", "Add view failed", e) }
        }
    }

    private fun removeBatteryOverlay() {
        batteryOverlayView?.let {
            it.findViewById<VideoView>(R.id.overlay_video)?.stopPlayback()
            it.removeCallbacks(batteryAutoCloseRunnable)
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            batteryOverlayView = null
            resumeTimer()
        }
    }

    private fun removeProximityOverlay() {
        proximityOverlayView?.let {
            it.findViewById<VideoView>(R.id.overlay_video)?.stopPlayback()
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            proximityOverlayView = null
            resumeTimer()
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            it.findViewById<VideoView>(R.id.overlay_video)?.stopPlayback()
            it.removeCallbacks(autoCloseRunnable)
            try { windowManager?.removeView(it) } catch (e: Exception) {}
            overlayView = null
            resumeTimer()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Child Alert Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Child Alert Timer").setContentText(text)
            .setSmallIcon(R.drawable.duck).setContentIntent(pendingIntent)
            .setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onDestroy() {
        countDownTimer?.cancel()
        removeOverlay()
        removeProximityOverlay()
        removeBatteryOverlay()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
