package com.childAlerts.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.childAlerts.app.ui.theme.ChildAlertTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Start Timer Service
        val serviceIntent = Intent(this, TimerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            ChildAlertTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val database = remember {
        Firebase.database("https://childalert-d49c3-default-rtdb.asia-southeast1.firebasedatabase.app")
    }
    val switchRef = remember { database.getReference("switch/active") }
    val timerRef = remember { database.getReference("timer") }
    val mediaRef = remember { database.getReference("media") }

    var isActive by remember { mutableStateOf(false) }
    var timerValue by remember { mutableLongStateOf(0L) }
    var timerType by remember { mutableStateOf("minutes") }
    var mediaType by remember { mutableStateOf("image") }

    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Permission Handlers
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Check Overlay Permission
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }

    // Mendengarkan perubahan di Firebase secara realtime
    LaunchedEffect(Unit) {
        switchRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                isActive = snapshot.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        timerRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                timerValue = snapshot.child("value").getValue(Long::class.java) ?: 0L
                timerType = snapshot.child("type").getValue(String::class.java) ?: "minutes"
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        mediaRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                mediaType = snapshot.child("type").getValue(String::class.java) ?: "image"
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    val mediaPlayer = remember {
        try {
            MediaPlayer.create(context, R.raw.toggle)
        } catch (e: Exception) {
            null
        }
    }

    val topColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFF1A237E) else Color(0xFF000000),
        animationSpec = tween(600)
    )
    val bottomColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFF0D1117) else Color(0xFF121212),
        animationSpec = tween(600)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
    ) {
        // Settings Button at Top Right
        IconButton(
            onClick = { showBottomSheet = true },
            modifier = Modifier
                .statusBarsPadding()
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.duck),
                    contentDescription = "Duck Logo",
                    modifier = Modifier.size(110.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Child Alert",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            
            Text(
                text = if (isActive) "Active" else "Inactive",
                color = if (isActive) Color(0xFF81D4FA) else Color.White.copy(alpha = 0.5f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "Timer: $timerValue $timerType | Media: $mediaType",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(80.dp))

            NeumorphicToggle(
                isActive = isActive,
                onToggleChange = { newState ->
                    switchRef.setValue(newState)
                    mediaPlayer?.apply {
                        try {
                            if (isPlaying) pause()
                            seekTo(0)
                            start()
                        } catch (e: Exception) {}
                    }
                }
            )
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White,
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) },
                contentWindowInsets = { WindowInsets.ime }
            ) {
                TimerSettingsContent(
                    currentValue = timerValue,
                    currentType = timerType,
                    currentMediaType = mediaType,
                    onSave = { newValue, newType, newMediaType ->
                        timerRef.updateChildren(mapOf("value" to newValue, "type" to newType))
                        mediaRef.updateChildren(mapOf("type" to newMediaType))
                        showBottomSheet = false
                    }
                )
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }
}

@Composable
fun TimerSettingsContent(
    currentValue: Long,
    currentType: String,
    currentMediaType: String,
    onSave: (Long, String, String) -> Unit
) {
    var textValue by remember { mutableStateOf(currentValue.toString()) }
    var selectedType by remember { mutableStateOf(currentType) }
    var selectedMediaType by remember { mutableStateOf(currentMediaType) }
    
    val timerTypes = listOf("minutes", "hours")
    val mediaTypes = listOf("image", "video")
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 48.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = "Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = textValue,
            onValueChange = { if (it.all { char -> char.isDigit() }) textValue = it },
            label = { Text("Timer Duration", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF81D4FA),
                unfocusedBorderColor = Color.Gray
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Timer Unit", color = Color.White, fontWeight = FontWeight.Medium)
        timerTypes.forEach { type ->
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .selectable(selected = (type == selectedType), onClick = { selectedType = type }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = (type == selectedType), onClick = { selectedType = type }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF81D4FA)))
                Text(text = type.replaceFirstChar { it.uppercase() }, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Media Type", color = Color.White, fontWeight = FontWeight.Medium)
        mediaTypes.forEach { type ->
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .selectable(selected = (type == selectedMediaType), onClick = { selectedMediaType = type }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = (type == selectedMediaType), onClick = { selectedMediaType = type }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF81D4FA)))
                Text(text = type.replaceFirstChar { it.uppercase() }, color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                val value = textValue.toLongOrNull() ?: 0L
                onSave(value, selectedType, selectedMediaType)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81D4FA)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save Settings", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun NeumorphicToggle(
    isActive: Boolean,
    onToggleChange: (Boolean) -> Unit
) {
    val duration = 400
    val trackWidth = 260.dp
    val trackHeight = 80.dp
    val thumbWidth = 120.dp
    
    val thumbOffset by animateDpAsState(
        targetValue = if (isActive) (trackWidth - thumbWidth - 8.dp) else 4.dp,
        animationSpec = tween(duration)
    )

    val thumbGradient = if (isActive) {
        Brush.linearGradient(listOf(Color(0xFFE0EAFC), Color(0xFFCFDEF3)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFFDC830), Color(0xFFF37335)))
    }

    Box(
        modifier = Modifier
            .width(trackWidth)
            .height(trackHeight)
            .clip(RoundedCornerShape(40.dp))
            .background(Color.Black)
            .border(2.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(40.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onToggleChange(!isActive)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = "Switch",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 24.dp)
        )

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(34.dp))
                .background(thumbGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.Black
            )
        }
    }
}
