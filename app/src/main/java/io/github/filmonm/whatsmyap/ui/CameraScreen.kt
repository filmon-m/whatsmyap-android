package io.github.filmonm.whatsmyap.ui

import androidx.annotation.OptIn
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import io.github.filmonm.whatsmyap.data.LabelReader
import io.github.filmonm.whatsmyap.data.Storage
import java.io.File
import java.util.concurrent.Executors

/** In-app camera: live label reading in the viewfinder, then the full-size photo is saved. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen(vm: FieldViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var live by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val capture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO).build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(0f) }               // 0..1 across the camera's zoom range
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var torch by remember { mutableStateOf(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastAnalysis = remember { longArrayOf(0L) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    // pinch to zoom, tap to focus
    DisposableEffect(previewView) {
        val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camera ?: return true
                val z = c.cameraInfo.zoomState.value ?: return true
                c.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP && !pinch.isInProgress && e.eventTime - e.downTime < 300) {
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
        onDispose { previewView.setOnTouchListener(null) }
    }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val now = System.currentTimeMillis()
                val media = proxy.image
                if (media == null || now - lastAnalysis[0] < 600) { proxy.close(); return@setAnalyzer }
                lastAnalysis[0] = now
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                LabelReader.recognizer.process(image)
                    .addOnSuccessListener { t -> live = LabelReader.pick(t.text)?.raw }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis).also { c ->
                    c.cameraInfo.zoomState.observe(owner) { z -> zoom = z.linearZoom; zoomRatio = z.zoomRatio }
                }
            } catch (e: Exception) {
                error = "Camera unavailable: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    fun take() {
        busy = true
        val file = File(Storage.photos(context, Storage.month()), Storage.fileStamp() + ".jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    vm.onPhotoTaken(file)
                    onDone()
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    error = "Couldn't save the photo: ${exception.message}"
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // flash for the photo, and a steady light for dark rooms (also helps focus and the live reading)
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            Row(
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp).background(Color(0xAA000000)),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = {
                    flash = when (flash) {
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                        else -> ImageCapture.FLASH_MODE_AUTO
                    }
                    capture.flashMode = flash
                }) {
                    Text("Flash: " + when (flash) { ImageCapture.FLASH_MODE_ON -> "On"; ImageCapture.FLASH_MODE_OFF -> "Off"; else -> "Auto" },
                        color = Color.White)
                }
                TextButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
                    Text(if (torch) "Light: On" else "Light: Off", color = if (torch) Color(0xFFFFD54F) else Color.White)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                live?.let { "Label in view: $it" } ?: "Aim at the AP label - fill the frame, avoid glare",
                color = if (live != null) Good else Color.White,
                fontWeight = if (live != null) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.titleMedium,
            )
            error?.let { Text(it, color = Bad) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zoom %.1fx".format(zoomRatio), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Slider(value = zoom, onValueChange = { zoom = it; camera?.cameraControl?.setLinearZoom(it) }, modifier = Modifier.weight(1f))
            }
            Text("Pinch to zoom, tap to focus", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { take() }, enabled = !busy) { Text(if (busy) "Saving..." else "Take photo") }
                OutlinedButton(onClick = onDone) { Text("Cancel", color = Color.White) }
            }
        }
    }
}
