package com.qrverify.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var preview: PreviewView
    private lateinit var resultCard: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var contentText: TextView
    private lateinit var actions: LinearLayout
    private lateinit var statusText: TextView

    private lateinit var cameraController: LifecycleCameraController

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var lastValue: String? = null
    private var noCodeFrames = 0

    private var currentValue: String? = null
    private var currentType = "Text"

    private val prefs by lazy {
        getSharedPreferences("qr_verify", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildInterface()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                41
            )
        }
    }

    private fun buildInterface() {

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        root.addView(
            preview,
            FrameLayout.LayoutParams(-1, -1)
        )

        // Top navigation

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.argb(225, 15, 15, 15))
        }

        val appTitle = TextView(this).apply {
            text = "QR Verify"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        top.addView(
            appTitle,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val historyButton = TextView(this).apply {
            text = "HISTORY"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(14, 10, 4, 10)
            setOnClickListener { showHistory() }
        }

        top.addView(historyButton)

        root.addView(
            top,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        )

        // Scanner status

        statusText = TextView(this).apply {
            text = "Point camera at a QR code"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }

        val statusParams = FrameLayout.LayoutParams(
            -1, -2, Gravity.TOP
        ).apply {
            topMargin = 66
        }

        root.addView(statusText, statusParams)

        // Bottom popup

        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 18)
            setBackgroundColor(Color.WHITE)
            elevation = 18f
            visibility = View.GONE
        }

        val handle = TextView(this).apply {
            text = "━━━"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }

        resultCard.addView(handle)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        header.addView(
            titleText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.DKGRAY)
            setPadding(12, 4, 4, 8)
            setOnClickListener {
                resultCard.visibility = View.GONE
            }
        }

        header.addView(close)
        resultCard.addView(header)

        contentText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 12, 0, 14)
            setTextIsSelectable(true)
        }

        resultCard.addView(contentText)

        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        resultCard.addView(actions)

        val cardParams = FrameLayout.LayoutParams(
            -1, -2, Gravity.BOTTOM
        ).apply {
            setMargins(10, 0, 10, 12)
        }

        root.addView(resultCard, cardParams)

        setContentView(root)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 41 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            statusText.text = "Camera permission is required."
        }
    }

    // CAMERA INITIALISATION

    private fun startCamera() {

        try {

            cameraController = LifecycleCameraController(this)

            cameraController.cameraSelector =
                CameraSelector.DEFAULT_BACK_CAMERA

            cameraController.setEnabledUseCases(
                CameraController.IMAGE_ANALYSIS
            )

            cameraController.setImageAnalysisAnalyzer(
                cameraExecutor
            ) { imageProxy ->

                processImage(imageProxy)
            }

            preview.controller = cameraController

            cameraController.bindToLifecycle(this)

            statusText.text = "Point camera at a QR code"

        } catch (e: Exception) {

            statusText.text =
                "Unable to start camera: ${e.localizedMessage}"
        }
    }

    // QR DETECTION

    private val scanner by lazy {

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        BarcodeScanning.getClient(options)
    }

    private fun processImage(imageProxy: ImageProxy) {

        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image

        if (mediaImage == null) {
            imageProxy.close()
            busy.set(false)
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(image)
            .addOnSuccessListener { codes ->

                val raw = codes.firstOrNull()?.rawValue

                if (raw.isNullOrBlank()) {

                    noCodeFrames++

                    if (noCodeFrames >= 8) {
                        lastValue = null
                    }

                } else {

                    noCodeFrames = 0

                    if (raw != lastValue) {

                        lastValue = raw

                        runOnUiThread {
                            showResult(raw)
                        }
                    }
                }
            }
            .addOnFailureListener {
                // Ignore individual frame errors.
            }
            .addOnCompleteListener {
                imageProxy.close()
                busy.set(false)
            }
    }

    // DISPLAY QR RESULT

    private fun showResult(raw: String) {

        currentValue = raw

        currentType = when {

            raw.trimStart().startsWith(
                "BEGIN:VCARD", true
            ) -> "Contact (vCard)"

            raw.trim().matches(
                Regex("(?i)^(https?://|www\\.).+")
            ) -> "Website URL"

            else -> "Text"
        }

        titleText.text = currentType

        contentText.text =
            if (currentType == "Contact (vCard)") {
                formatVCard(raw)
            } else {
                raw
            }

        actions.removeAllViews()

        when (currentType) {

            "Website URL" -> {

                addAction("COPY") {
                    copy(raw)
                }

                addAction("OPEN URL") {

                    val target =
                        if (raw.startsWith("www.", true)) {
                            "https://$raw"
                        } else {
                            raw
                        }

                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(target)
                            )
                        )
                    } catch (_: Exception) {
                        Toast.makeText(
                            this,
                            "Unable to open URL",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            "Contact (vCard)" -> {

                addAction("COPY TEXT") {
                    copy(raw)
                }

                addAction("VIEW VCARD") {
                    showFullText(raw)
                }
            }

            else -> {

                addAction("COPY TEXT") {
                    copy(raw)
                }
            }
        }

        saveHistory(raw, currentType)

        statusText.text = "QR detected • Scan the next code"

        resultCard.visibility = View.VISIBLE
    }

    // CONTACT CARD PREVIEW

    private fun formatVCard(raw: String): String {

        val fields = raw.lines().mapNotNull { line ->

            val p = line.indexOf(':')

            if (p < 0) {
                null
            } else {

                line.substring(0, p)
                    .substringAfterLast(';')
                    .substringAfterLast('.') to
                    line.substring(p + 1)
            }
        }

        val labels = mapOf(
            "FN" to "Name",
            "ORG" to "Company",
            "TEL" to "Phone",
            "EMAIL" to "Email",
            "URL" to "Website"
        )

        return fields
            .filter { labels.containsKey(it.first.uppercase()) }
            .joinToString("\n") {
                "${labels[it.first.uppercase()]}: ${it.second}"
            }
            .ifBlank {
                "Contact card detected"
            }
    }

    private fun showFullText(raw: String) {
        contentText.text = raw
    }

    // POPUP BUTTONS

    private fun addAction(
        label: String,
        action: () -> Unit
    ) {

        val button = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(18, 14, 18, 14)
            setBackgroundColor(Color.rgb(35, 35, 35))

            setOnClickListener {
                action()
            }
        }

        val lp = LinearLayout.LayoutParams(
            0, -2, 1f
        ).apply {
            setMargins(4, 4, 4, 4)
        }

        actions.addView(button, lp)
    }

    // COPY CONTENT

    private fun copy(value: String) {

        val clipboard =
            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText("QR content", value)
        )

        Toast.makeText(
            this,
            "Copied",
            Toast.LENGTH_SHORT
        ).show()
    }

    // LOCAL SCAN HISTORY

    private fun saveHistory(raw: String, type: String) {

        val old = try {
            JSONArray(prefs.getString("history", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        val arr = JSONArray()

        arr.put(
            JSONObject()
                .put("value", raw)
                .put("type", type)
                .put("time", System.currentTimeMillis())
        )

        for (i in 0 until minOf(old.length(), 99)) {
            arr.put(old.getJSONObject(i))
        }

        prefs.edit()
            .putString("history", arr.toString())
            .apply()
    }

    private fun showHistory() {

        val arr = try {
            JSONArray(prefs.getString("history", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        val lines = (0 until arr.length()).map { i ->

            val item = arr.getJSONObject(i)

            "${item.optString("type")}\n${item.optString("value")}"
        }

        val body = if (lines.isEmpty()) {
            "No scans yet."
        } else {
            lines.joinToString("\n\n────────────\n\n")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Scan history")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear history") { _, _ ->

                prefs.edit()
                    .putString("history", "[]")
                    .apply()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::cameraController.isInitialized) {
            cameraController.clearImageAnalysisAnalyzer()
            cameraController.unbind()
        }

        scanner.close()
        cameraExecutor.shutdown()
    }
}
