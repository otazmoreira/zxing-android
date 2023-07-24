/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.tavieto.scanner.zxing

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.ResultPoint
import com.google.zxing.client.android.R
import dev.tavieto.scanner.zxing.camera.CameraManager
import dev.tavieto.scanner.zxing.clipboard.ClipboardInterface
import dev.tavieto.scanner.zxing.result.ResultButtonListener
import dev.tavieto.scanner.zxing.result.ResultHandler
import dev.tavieto.scanner.zxing.result.ResultHandlerFactory
import java.io.IOException
import java.text.DateFormat
import java.util.EnumSet

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
class CaptureActivity : Activity(), SurfaceHolder.Callback {
    var cameraManager: CameraManager? = null
        private set
    private var handler: CaptureActivityHandler? = null
    private var savedResultToShow: Result? = null
    var viewfinderView: ViewfinderView? = null
        private set
    private var statusView: TextView? = null
    private var resultView: View? = null
    private var lastResult: Result? = null
    private var hasSurface = false
    private var copyToClipboard = false
    private var source: IntentSource? = null
    private var sourceUrl: String? = null
    private var scanFromWebPageManager: ScanFromWebPageManager? = null
    private var decodeFormats: Collection<BarcodeFormat>? = null
    private var decodeHints: Map<DecodeHintType, *>? = null
    private var characterSet: String? = null
    private var inactivityTimer: InactivityTimer? = null
    private var ambientLightManager: AmbientLightManager? = null

    fun getHandler(): Handler? {
        return handler
    }

    public override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        setContentView(R.layout.capture)
        hasSurface = false
        inactivityTimer = InactivityTimer(this)
        ambientLightManager = AmbientLightManager(this)
    }

    override fun onResume() {
        super.onResume()

        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        cameraManager = CameraManager(application)
        viewfinderView = findViewById<View>(R.id.viewfinder_view) as ViewfinderView
        viewfinderView!!.setCameraManager(cameraManager)

        resultView = findViewById(R.id.result_view)
        statusView = findViewById<View>(R.id.status_view) as TextView
        handler = null
        lastResult = null
        requestedOrientation = currentOrientation
        resetStatusView()
        ambientLightManager!!.start(cameraManager)
        inactivityTimer!!.onResume()
        val intent = intent
        copyToClipboard = intent == null || intent.getBooleanExtra(
            Intents.Scan.SAVE_HISTORY,
            true
        )
        source = IntentSource.NONE
        sourceUrl = null
        scanFromWebPageManager = null
        decodeFormats = null
        characterSet = null
        if (intent != null) {
            val action = intent.action
            val dataString = intent.dataString
            if (Intents.Scan.ACTION == action) {
                // Scan the formats the intent requested, and return the result to the calling activity.
                source = IntentSource.NATIVE_APP_INTENT
                decodeFormats = DecodeFormatManager.parseDecodeFormats(intent)
                decodeHints = DecodeHintManager.parseDecodeHints(intent)
                if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
                    val width = intent.getIntExtra(Intents.Scan.WIDTH, 0)
                    val height = intent.getIntExtra(Intents.Scan.HEIGHT, 0)
                    if (width > 0 && height > 0) {
                        cameraManager!!.setManualFramingRect(width, height)
                    }
                }
                if (intent.hasExtra(Intents.Scan.CAMERA_ID)) {
                    val cameraId = intent.getIntExtra(Intents.Scan.CAMERA_ID, -1)
                    if (cameraId >= 0) {
                        cameraManager!!.setManualCameraId(cameraId)
                    }
                }
                val customPromptMessage = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE)
                if (customPromptMessage != null) {
                    statusView!!.text = customPromptMessage
                }
            } else if (dataString != null &&
                dataString.contains("http://www.google") &&
                dataString.contains("/m/products/scan")
            ) {

                // Scan only products and send the result to mobile Product Search.
                source = IntentSource.PRODUCT_SEARCH_LINK
                sourceUrl = dataString
                decodeFormats = DecodeFormatManager.PRODUCT_FORMATS
            } else if (isZXingURL(dataString)) {

                // Scan formats requested in query string (all formats if none specified).
                // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
                source = IntentSource.ZXING_LINK
                sourceUrl = dataString
                val inputUri = Uri.parse(dataString)
                scanFromWebPageManager = ScanFromWebPageManager(inputUri)
                decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri)
                // Allow a sub-set of the hints to be specified by the caller.
                decodeHints = DecodeHintManager.parseDecodeHints(inputUri)
            }
            characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET)
        }
        val surfaceView = findViewById<View>(R.id.preview_view) as SurfaceView
        val surfaceHolder = surfaceView.holder
        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder)
        } else {
            // Install the callback and wait for surfaceCreated() to init the camera.
            surfaceHolder.addCallback(this)
        }
    }

    private val currentOrientation: Int
        private get() {
            val rotation = windowManager.defaultDisplay.rotation
            return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                when (rotation) {
                    Surface.ROTATION_0, Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                }
            } else {
                when (rotation) {
                    Surface.ROTATION_0, Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                }
            }
        }

    override fun onPause() {
        if (handler != null) {
            handler!!.quitSynchronously()
            handler = null
        }
        inactivityTimer!!.onPause()
        ambientLightManager!!.stop()
//        beepManager!!.close()
        cameraManager!!.closeDriver()
        //historyManager = null; // Keep for onActivityResult
        if (!hasSurface) {
            val surfaceView = findViewById<View>(R.id.preview_view) as SurfaceView
            val surfaceHolder = surfaceView.holder
            surfaceHolder.removeCallback(this)
        }
        super.onPause()
    }

    override fun onDestroy() {
        inactivityTimer!!.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (source == IntentSource.NATIVE_APP_INTENT) {
                    setResult(RESULT_CANCELED)
                    finish()
                    return true
                }
                if ((source == IntentSource.NONE || source == IntentSource.ZXING_LINK) && lastResult != null) {
                    restartPreviewAfterDelay(0L)
                    return true
                }
            }

            KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_CAMERA ->         // Handle these events so they don't launch the Camera app
                return true

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                cameraManager!!.setTorch(false)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                cameraManager!!.setTorch(true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val menuInflater = menuInflater
        menuInflater.inflate(R.menu.capture, menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun decodeOrStoreSavedBitmap(result: Result?) {
        // Bitmap isn't used yet -- will be used soon
        if (handler == null) {
            savedResultToShow = result
        } else {
            if (result != null) {
                savedResultToShow = result
            }
            if (savedResultToShow != null) {
                val message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow)
                handler!!.sendMessage(message)
            }
            savedResultToShow = null
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (holder == null) {
            Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!")
        }
        if (!hasSurface) {
            hasSurface = true
            initCamera(holder)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // do nothing
    }

    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     *
     * @param rawResult The contents of the barcode.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param barcode   A greyscale bitmap of the camera data which was decoded.
     */
    // TODO - recebe o resultado final
    fun handleDecode(rawResult: Result, barcode: Bitmap?, scaleFactor: Float) {
        inactivityTimer!!.onActivity()
        lastResult = rawResult
        val resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult)
        val fromLiveScan = barcode != null
        if (fromLiveScan) {
            // Then not from history, so beep/vibrate and we have an image to draw on
//            beepManager!!.playBeepSoundAndVibrate()
            drawResultPoints(barcode, scaleFactor, rawResult)
        }
        when (source) {
            IntentSource.NATIVE_APP_INTENT,
            IntentSource.PRODUCT_SEARCH_LINK -> {
                handleDecodeExternally(rawResult, resultHandler, barcode)
            }

            IntentSource.ZXING_LINK -> {
                if (scanFromWebPageManager == null || !scanFromWebPageManager!!.isScanFromWebPage) {
                    handleDecodeInternally(rawResult, resultHandler, barcode)
                } else {
                    handleDecodeExternally(rawResult, resultHandler, barcode)
                }
            }

            IntentSource.NONE -> {
                handleDecodeInternally(rawResult, resultHandler, barcode)
            }

            else -> Unit
        }
    }

    // desenha os pontos principais do qr code quando encontra em cima da foto
    /**
     * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
     *
     * @param barcode   A bitmap of the captured image.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param rawResult The decoded results which contains the points to draw.
     */
    private fun drawResultPoints(barcode: Bitmap?, scaleFactor: Float, rawResult: Result) {
        val points = rawResult.resultPoints
        if (points != null && points.size > 0) {
            val canvas = Canvas(barcode!!)
            val paint = Paint()
            paint.color = resources.getColor(R.color.result_points)
            if (points.size == 2) {
                paint.strokeWidth = 4.0f
                drawLine(canvas, paint, points[0], points[1], scaleFactor)
            } else if (points.size == 4 &&
                (rawResult.barcodeFormat == BarcodeFormat.UPC_A ||
                        rawResult.barcodeFormat == BarcodeFormat.EAN_13)
            ) {
                // Hacky special case -- draw two lines, for the barcode and metadata
                drawLine(canvas, paint, points[0], points[1], scaleFactor)
                drawLine(canvas, paint, points[2], points[3], scaleFactor)
            } else {
                paint.strokeWidth = 10.0f
                for (point in points) {
                    if (point != null) {
                        canvas.drawPoint(scaleFactor * point.x, scaleFactor * point.y, paint)
                    }
                }
            }
        }
    }

    // apenas mostra o conteúdo escaneado na tela
    // Put up our own UI for how to handle the decoded contents.
    private fun handleDecodeInternally(
        rawResult: Result,
        resultHandler: ResultHandler,
        barcode: Bitmap?
    ) {
        maybeSetClipboard(resultHandler)
        if (resultHandler.defaultButtonID != null) {
            resultHandler.handleButtonPress(resultHandler.defaultButtonID)
            return
        }
        statusView!!.visibility = View.GONE
        viewfinderView!!.visibility = View.GONE
        resultView!!.visibility = View.VISIBLE
        val barcodeImageView = findViewById<View>(R.id.barcode_image_view) as ImageView
        if (barcode == null) {
            barcodeImageView.setImageBitmap(
                BitmapFactory.decodeResource(
                    resources,
                    R.drawable.launcher_icon
                )
            )
        } else {
            barcodeImageView.setImageBitmap(barcode)
        }
        val formatTextView = findViewById<View>(R.id.format_text_view) as TextView
        formatTextView.text = rawResult.barcodeFormat.toString()
        val typeTextView = findViewById<View>(R.id.type_text_view) as TextView
        typeTextView.text = resultHandler.type.toString()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val timeTextView = findViewById<View>(R.id.time_text_view) as TextView
        timeTextView.text = formatter.format(rawResult.timestamp)
        val metaTextView = findViewById<View>(R.id.meta_text_view) as TextView
        val metaTextViewLabel = findViewById<View>(R.id.meta_text_view_label)
        metaTextView.visibility = View.GONE
        metaTextViewLabel.visibility = View.GONE
        val metadata = rawResult.resultMetadata
        if (metadata != null) {
            val metadataText = StringBuilder(20)
            for ((key, value) in metadata) {
                if (DISPLAYABLE_METADATA_TYPES.contains(key)) {
                    metadataText.append(value).append('\n')
                }
            }
            if (metadataText.length > 0) {
                metadataText.setLength(metadataText.length - 1)
                metaTextView.text = metadataText
                metaTextView.visibility = View.VISIBLE
                metaTextViewLabel.visibility = View.VISIBLE
            }
        }
        val displayContents = resultHandler.displayContents
        val contentsTextView = findViewById<View>(R.id.contents_text_view) as TextView
        contentsTextView.text = displayContents
        val scaledSize = Math.max(22, 32 - displayContents.length / 4)
        contentsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSize.toFloat())
        val supplementTextView = findViewById<View>(R.id.contents_supplement_text_view) as TextView
        supplementTextView.text = ""
        supplementTextView.setOnClickListener(null)
        val buttonCount = resultHandler.buttonCount
        val buttonView = findViewById<View>(R.id.result_button_view) as ViewGroup
        buttonView.requestFocus()
        for (x in 0 until ResultHandler.MAX_BUTTON_COUNT) {
            val button = buttonView.getChildAt(x) as TextView
            if (x < buttonCount) {
                button.visibility = View.VISIBLE
                button.setText(resultHandler.getButtonText(x))
                button.setOnClickListener(ResultButtonListener(resultHandler, x))
            } else {
                button.visibility = View.GONE
            }
        }
    }

    // irrelevante
    // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
    private fun handleDecodeExternally(
        rawResult: Result,
        resultHandler: ResultHandler,
        barcode: Bitmap?
    ) {
        if (barcode != null) {
            viewfinderView!!.drawResultBitmap(barcode)
        }
        val resultDurationMS: Long = if (intent == null) {
            DEFAULT_INTENT_RESULT_DURATION_MS
        } else {
            intent.getLongExtra(
                Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                DEFAULT_INTENT_RESULT_DURATION_MS
            )
        }
        if (resultDurationMS > 0) {
            var rawResultString = rawResult.toString()
            if (rawResultString.length > 32) {
                rawResultString = rawResultString.substring(0, 32) + " ..."
            }
            statusView!!.text = getString(resultHandler.displayTitle) + " : " + rawResultString
        }
        maybeSetClipboard(resultHandler)
        when (source) {
            IntentSource.NATIVE_APP_INTENT -> {
                // Hand back whatever action they requested - this can be changed to Intents.Scan.ACTION when
                // the deprecated intent is retired.
                val intent = Intent(intent.action)
                intent.addFlags(Intents.FLAG_NEW_DOC)
                intent.putExtra(Intents.Scan.RESULT, rawResult.toString())
                intent.putExtra(Intents.Scan.RESULT_FORMAT, rawResult.barcodeFormat.toString())
                val rawBytes = rawResult.rawBytes
                if (rawBytes != null && rawBytes.size > 0) {
                    intent.putExtra(Intents.Scan.RESULT_BYTES, rawBytes)
                }
                val metadata = rawResult.resultMetadata
                if (metadata != null) {
                    if (metadata.containsKey(ResultMetadataType.UPC_EAN_EXTENSION)) {
                        intent.putExtra(
                            Intents.Scan.RESULT_UPC_EAN_EXTENSION,
                            metadata[ResultMetadataType.UPC_EAN_EXTENSION].toString()
                        )
                    }
                    val orientation = metadata[ResultMetadataType.ORIENTATION] as Number?
                    if (orientation != null) {
                        intent.putExtra(Intents.Scan.RESULT_ORIENTATION, orientation.toInt())
                    }
                    val ecLevel = metadata[ResultMetadataType.ERROR_CORRECTION_LEVEL] as String?
                    if (ecLevel != null) {
                        intent.putExtra(Intents.Scan.RESULT_ERROR_CORRECTION_LEVEL, ecLevel)
                    }
                    val byteSegments =
                        metadata[ResultMetadataType.BYTE_SEGMENTS] as Iterable<ByteArray>?
                    if (byteSegments != null) {
                        var i = 0
                        for (byteSegment in byteSegments) {
                            intent.putExtra(
                                Intents.Scan.RESULT_BYTE_SEGMENTS_PREFIX + i,
                                byteSegment
                            )
                            i++
                        }
                    }
                }
                sendReplyMessage(R.id.return_scan_result, intent, resultDurationMS)
            }

            IntentSource.PRODUCT_SEARCH_LINK -> {
                // Reformulate the URL which triggered us into a query, so that the request goes to the same
                // TLD as the scan URL.
                val end = sourceUrl!!.lastIndexOf("/scan")
                val productReplyURL = sourceUrl!!.substring(0, end) + "?q=" +
                        resultHandler.displayContents + "&source=zxing"
                sendReplyMessage(R.id.launch_product_query, productReplyURL, resultDurationMS)
            }

            IntentSource.ZXING_LINK -> if (scanFromWebPageManager != null && scanFromWebPageManager!!.isScanFromWebPage) {
                val linkReplyURL = scanFromWebPageManager!!.buildReplyURL(rawResult, resultHandler)
                scanFromWebPageManager = null
                sendReplyMessage(R.id.launch_product_query, linkReplyURL, resultDurationMS)
            }

            else -> Unit
        }
    }

    // irrelevante - apenas coloca o texto o clipboard
    private fun maybeSetClipboard(resultHandler: ResultHandler) {
        if (copyToClipboard && !resultHandler.areContentsSecure()) {
            ClipboardInterface.setText(resultHandler.displayContents, this)
        }
    }

    private fun sendReplyMessage(id: Int, arg: Any, delayMS: Long) {
        if (handler != null) {
            val message = Message.obtain(handler, id, arg)
            if (delayMS > 0L) {
                handler!!.sendMessageDelayed(message, delayMS)
            } else {
                handler!!.sendMessage(message)
            }
        }
    }

    private fun initCamera(surfaceHolder: SurfaceHolder?) {
        checkNotNull(surfaceHolder) { "No SurfaceHolder provided" }
        if (cameraManager!!.isOpen) {
            Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?")
            return
        }
        try {
            cameraManager!!.openDriver(surfaceHolder)
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (handler == null) {
                handler = CaptureActivityHandler(
                    this,
                    decodeFormats,
                    decodeHints,
                    characterSet,
                    cameraManager
                )
            }
        } catch (ioe: IOException) {
            Log.w(TAG, ioe)
            displayFrameworkBugMessageAndExit()
        } catch (e: RuntimeException) {
            // Barcode Scanner has seen crashes in the wild of this variety:
            // java.?lang.?RuntimeException: Fail to connect to camera service
            Log.w(TAG, "Unexpected error initializing camera", e)
            displayFrameworkBugMessageAndExit()
        }
    }

    private fun displayFrameworkBugMessageAndExit() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.app_name))
        builder.setMessage(getString(R.string.msg_camera_framework_bug))
        builder.setPositiveButton(R.string.button_ok, FinishListener(this))
        builder.setOnCancelListener(FinishListener(this))
        builder.show()
    }

    fun restartPreviewAfterDelay(delayMS: Long) {
        if (handler != null) {
            handler!!.sendEmptyMessageDelayed(R.id.restart_preview, delayMS)
        }
        resetStatusView()
    }

    // reicinia a tela pra escanear novamente
    private fun resetStatusView() {
        resultView!!.visibility = View.GONE
        statusView!!.setText(R.string.msg_default_status)
        statusView!!.visibility = View.VISIBLE
        viewfinderView!!.visibility = View.VISIBLE
        lastResult = null
    }

    fun drawViewfinder() {
        viewfinderView!!.drawViewfinder()
    }

    companion object {
        private val TAG = CaptureActivity::class.java.simpleName
        private const val DEFAULT_INTENT_RESULT_DURATION_MS = 1500L
        private val ZXING_URLS = arrayOf("http://zxing.appspot.com/scan", "zxing://scan/")
        private val DISPLAYABLE_METADATA_TYPES: Collection<ResultMetadataType> = EnumSet.of(
            ResultMetadataType.ISSUE_NUMBER,
            ResultMetadataType.SUGGESTED_PRICE,
            ResultMetadataType.ERROR_CORRECTION_LEVEL,
            ResultMetadataType.POSSIBLE_COUNTRY
        )

        private fun isZXingURL(dataString: String?): Boolean {
            if (dataString == null) {
                return false
            }
            for (url in ZXING_URLS) {
                if (dataString.startsWith(url)) {
                    return true
                }
            }
            return false
        }

        private fun drawLine(
            canvas: Canvas,
            paint: Paint,
            a: ResultPoint?,
            b: ResultPoint?,
            scaleFactor: Float
        ) {
            if (a != null && b != null) {
                canvas.drawLine(
                    scaleFactor * a.x,
                    scaleFactor * a.y,
                    scaleFactor * b.x,
                    scaleFactor * b.y,
                    paint
                )
            }
        }
    }
}