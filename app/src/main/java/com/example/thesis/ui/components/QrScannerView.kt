package com.example.thesis.ui.components

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.thesis.transfer.DeviceTransferProtocol
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun QrScannerView(
    onPairingUriDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
    validator: (String) -> Boolean = { DeviceTransferProtocol.parsePairingUri(it) != null }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, previewView) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val hasEmitted = AtomicBoolean(false)
        val qrReader = QRCodeReader()
        val decodeHints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        var boundProvider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)

        providerFuture.addListener({
            runCatching {
                val cameraProvider = providerFuture.get()
                boundProvider = cameraProvider

                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        if (hasEmitted.get()) return@setAnalyzer
                        val decoded = decodeQrFromImage(imageProxy, qrReader, decodeHints) ?: return@setAnalyzer
                        if (validator(decoded) &&
                            hasEmitted.compareAndSet(false, true)
                        ) {
                            mainExecutor.execute { onPairingUriDetected(decoded) }
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure { err ->
                cameraError = err.message ?: "Αδυναμία εκκίνησης κάμερας"
            }
        }, mainExecutor)

        onDispose {
            runCatching { boundProvider?.unbindAll() }
            analysisExecutor.shutdownNow()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (cameraError != null) {
            Text(text = cameraError.orEmpty())
        } else {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun decodeQrFromImage(
    imageProxy: ImageProxy,
    reader: QRCodeReader,
    hints: Map<DecodeHintType, Any>
): String? {
    val width = imageProxy.width
    val height = imageProxy.height
    if (width <= 0 || height <= 0 || imageProxy.planes.isEmpty()) return null
    val yBytes = extractTightLumaBytes(imageProxy.planes[0], width, height)
    val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decode(bitmap, hints).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

// Normalizamos el plano Y fila a fila para tolerar padding de stride variable entre fabricantes Android.
private fun extractTightLumaBytes(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int
): ByteArray {
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (pixelStride == 1 && rowStride == width) {
        return ByteArray(width * height).also { buffer.get(it) }
    }
    val out = ByteArray(width * height)
    val rowBuffer = ByteArray(rowStride)
    val limit = buffer.remaining()
    for (row in 0 until height) {
        val toRead = minOf(rowStride, limit - buffer.position())
        if (toRead <= 0) break
        buffer.get(rowBuffer, 0, toRead)
        val outOffset = row * width
        if (pixelStride == 1) {
            System.arraycopy(rowBuffer, 0, out, outOffset, minOf(width, toRead))
        } else {
            var col = 0
            while (col < width && col * pixelStride < toRead) {
                out[outOffset + col] = rowBuffer[col * pixelStride]
                col++
            }
        }
    }
    return out
}
