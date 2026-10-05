package net.primal.android.pcoc.admit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.aakira.napier.Napier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.primal.android.LocalQrCodeDecoder
import net.primal.android.core.compose.foundation.KeepScreenOn
import net.primal.android.scanner.MissingCameraPermissionContent
import net.primal.android.scanner.analysis.QrCodeAnalyzer
import net.primal.android.scanner.analysis.QrCodeResultDecoder

/**
 * Full-frame scanner for the service sign-in QR.
 * The shared profile scanner animates a clipped preview up from 0dp, so CameraX can
 * keep an empty viewport, and it drops text that is not an already-known barcode type.
 */
@Composable
fun AdmissionQrScanner(onQrText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        mutableStateOf(permission == PackageManager.PERMISSION_GRANTED)
    }
    if (hasCameraPermission) {
        AdmissionCameraPreview(modifier = modifier, onQrText = onQrText)
    } else {
        MissingCameraPermissionContent(
            modifier = modifier,
            onPermissionChange = { allowed -> hasCameraPermission = allowed },
        )
    }
}

@Composable
private fun AdmissionCameraPreview(onQrText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val decoder = LocalQrCodeDecoder.current
    val onQrTextState = rememberUpdatedState(onQrText)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, decoder) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = AdmissionQrAnalyzer(decoder = decoder) { text ->
            val deliver = onQrTextState.value
            mainExecutor.execute { deliver(text) }
        }
        val controller = admissionCameraController(context = context, executor = executor, analyzer = analyzer)
        previewView.controller = controller
        controller.bindToLifecycle(lifecycleOwner)
        controller.initializationFuture.addListener(
            { enableContinuousFocus(controller) },
            mainExecutor,
        )
        onDispose {
            previewView.controller = null
            controller.unbind()
            executor.shutdown()
        }
    }

    Box(modifier = modifier) {
        KeepScreenOn()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { _ -> previewView },
        )
        AdmissionViewfinder(modifier = Modifier.fillMaxSize())
    }
}

private fun admissionCameraController(
    context: Context,
    executor: ExecutorService,
    analyzer: ImageAnalysis.Analyzer,
): LifecycleCameraController =
    LifecycleCameraController(context).apply {
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        QrCodeAnalyzer.AnalysisTargetSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build(),
        )
        setImageAnalysisAnalyzer(executor, analyzer)
    }

private fun enableContinuousFocus(controller: LifecycleCameraController) {
    try {
        val control = controller.cameraControl ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            .build()
        Camera2CameraControl.from(control).setCaptureRequestOptions(options)
    } catch (error: IllegalStateException) {
        Napier.w(error) { "Service admission camera focus was not set." }
    } catch (error: IllegalArgumentException) {
        Napier.w(error) { "Service admission camera focus was not set." }
    }
}

private class AdmissionQrAnalyzer(
    private val decoder: QrCodeResultDecoder,
    private val onText: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastText: String? = null
    private var lastAtMs: Long = 0L

    override fun analyze(image: ImageProxy) {
        val text = decoder.decodeText(image)?.trim().orEmpty()
        if (text.isNotEmpty() && accept(text)) {
            onText(text)
        }
    }

    private fun accept(text: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val repeating = text == lastText && now - lastAtMs < REPEAT_WINDOW_MS
        if (repeating) return false
        lastText = text
        lastAtMs = now
        return true
    }

    private companion object {
        const val REPEAT_WINDOW_MS = 1_500L
    }
}

@Composable
private fun AdmissionViewfinder(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val side = size.minDimension * VIEWFINDER_FRACTION
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val arm = VIEWFINDER_ARM.toPx()
        val stroke = Stroke(width = VIEWFINDER_STROKE.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White, Offset(left, top + arm), Offset(left, top), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(left, top), Offset(left + arm, top), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(right - arm, top), Offset(right, top), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(right, top), Offset(right, top + arm), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(left, bottom - arm), Offset(left, bottom), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(left, bottom), Offset(left + arm, bottom), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(right - arm, bottom), Offset(right, bottom), stroke.width, cap = stroke.cap)
        drawLine(Color.White, Offset(right, bottom - arm), Offset(right, bottom), stroke.width, cap = stroke.cap)
    }
}

private const val VIEWFINDER_FRACTION = 0.72f
private val VIEWFINDER_STROKE = 3.dp
private val VIEWFINDER_ARM = 28.dp
