package com.openpasskey.terminal.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openpasskey.terminal.provisioning.TerminalProvisioningPayloadCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun AddressScannerDialog(
    onDismiss: () -> Unit,
    onAddressScanned: (String) -> Unit,
) {
    ConfigurationQrScannerDialog(
        title = "Scan configuration address",
        instructions = "Address-only QR codes are accepted. Payment requests, WalletConnect, links, " +
            "and JSON are rejected and never acted on.",
        manualFallback = "You can still paste the address.",
        onDismiss = onDismiss,
        acceptPayload = { rawValue ->
            runCatching { AddressQrParser.parse(rawValue) }
                .fold(
                    onSuccess = { address ->
                        onAddressScanned(address)
                        null
                    },
                    onFailure = { "Not an address-only QR. No value was imported." },
                )
        },
    )
}

@Composable
internal fun ProvisioningScannerDialog(
    onDismiss: () -> Unit,
    onProvisioningPayloadScanned: (String) -> Unit,
) {
    ConfigurationQrScannerDialog(
        title = "Scan merchant portal",
        instructions = "Scan the unified OPK terminal provisioning QR shown by the merchant portal. " +
            "Payment requests, links, and address-only QRs are rejected.",
        manualFallback = "Return to the merchant portal and display its provisioning QR.",
        onDismiss = onDismiss,
        acceptPayload = { rawValue ->
            runCatching { TerminalProvisioningPayloadCodec.parse(rawValue) }
                .fold(
                    onSuccess = {
                        onProvisioningPayloadScanned(rawValue)
                        null
                    },
                    onFailure = { "Not a canonical OPK terminal provisioning QR." },
                )
        },
    )
}

@Composable
private fun ConfigurationQrScannerDialog(
    title: String,
    instructions: String,
    manualFallback: String,
    onDismiss: () -> Unit,
    acceptPayload: (String) -> String?,
) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var scanError by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    val dismissScanner = {
        accepted = true
        onDismiss()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) scanError = "Camera access was not granted. $manualFallback"
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = dismissScanner) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    instructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (permissionGranted) {
                    CameraQrPreview(
                        onBarcode = { rawValue ->
                            if (!accepted) {
                                val error = acceptPayload(rawValue)
                                if (error == null) {
                                    accepted = true
                                    onDismiss()
                                } else {
                                    scanError = error
                                }
                            }
                        },
                        onError = { scanError = "$it $manualFallback" },
                    )
                } else {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Text(" Allow camera")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        },
                    ) {
                        Text("Open app settings")
                    }
                }

                scanError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismissScanner) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun CameraQrPreview(
    onBarcode: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(lifecycleOwner, previewView) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analyzer = ConfigurationQrAnalyzer(
            mainExecutor = mainExecutor,
            onBarcode = { currentOnBarcode(it) },
            onError = { currentOnError(it) },
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val disposed = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null

        providerFuture.addListener(
            listener@{
                if (disposed.get()) return@listener
                runCatching {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    if (disposed.get()) return@runCatching
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }

                    val cameraSelector = when {
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> error("No usable camera")
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis,
                    )
                }.onFailure {
                    if (disposed.get()) return@onFailure
                    currentOnError("Camera is unavailable. You can still paste the address.")
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed.set(true)
            analysis?.clearAnalyzer()
            provider?.unbindAll()
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
    )
}

private class ConfigurationQrAnalyzer(
    private val mainExecutor: java.util.concurrent.Executor,
    private val onBarcode: (String) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val failureReported = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var lastValue: String? = null
    private var lastValueAtMillis: Long = 0
    private var lastDecodeAtNanos: Long = 0

    override fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }

        val nowNanos = System.nanoTime()
        if (nowNanos - lastDecodeAtNanos < MIN_DECODE_INTERVAL_NANOS) {
            imageProxy.close()
            return
        }
        lastDecodeAtNanos = nowNanos

        try {
            val plane = imageProxy.planes.firstOrNull()
                ?: error("Camera frame has no luminance plane")
            val luminance = OnDeviceQrDecoder.copyLuminancePlane(
                buffer = plane.buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            val value = OnDeviceQrDecoder.decode(
                luminance = luminance,
                width = imageProxy.width,
                height = imageProxy.height,
            )
            if (value != null && !closed.get()) {
                val now = System.currentTimeMillis()
                if (value != lastValue || now - lastValueAtMillis >= 1_500) {
                    lastValue = value
                    lastValueAtMillis = now
                    mainExecutor.execute {
                        if (!closed.get()) onBarcode(value)
                    }
                }
            }
        } catch (_: RuntimeException) {
            if (!closed.get() && failureReported.compareAndSet(false, true)) {
                mainExecutor.execute {
                    if (!closed.get()) {
                        onError("QR scanning failed. You can still paste the address.")
                    }
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        closed.set(true)
    }

    private companion object {
        const val MIN_DECODE_INTERVAL_NANOS = 150_000_000L
    }
}
