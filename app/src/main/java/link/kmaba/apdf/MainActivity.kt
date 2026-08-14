package link.kmaba.apdf

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PdfPrint
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private data class Picked(val name: String, val uri: Uri, val ext: String, val size: Long)

    private lateinit var listView: ListView
    private lateinit var btnConvert: Button
    private lateinit var btnAdd: Button
    private lateinit var btnClear: Button
    private lateinit var btnMode: Button

    private var separateMode = false

    private val picks = ArrayList<Picked>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var converting = false

    @Volatile private var doneLatch = CountDownLatch(1)
    @Volatile private var jsError: String? = null
    @Volatile private var pendingB64: String? = null

    private var pendingSave: File? = null
    private var pendingName: String? = null
    private var pendingSaves: List<Pair<File, String>> = emptyList()

    private lateinit var adapter: ArrayAdapter<Picked>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.fileList)
        btnConvert = findViewById(R.id.btnConvert)
        btnAdd = findViewById(R.id.btnAdd)
        btnClear = findViewById(R.id.btnClear)
        btnMode = findViewById(R.id.btnMode)

        adapter = object : ArrayAdapter<Picked>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, picks
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val p = getItem(position) ?: return v
                v.findViewById<TextView>(android.R.id.text1)?.apply {
                    text = p.name
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                }
                v.findViewById<TextView>(android.R.id.text2)?.apply {
                    text = formatSize(p.size)
                    setTextColor(0xFF9CA3AF.toInt())
                    textSize = 12f
                }
                return v
            }
        }
        listView.adapter = adapter

        btnAdd.setOnClickListener { pickFiles() }
        btnClear.setOnClickListener {
            picks.clear()
            refreshList()
        }
        btnConvert.setOnClickListener { startConversion() }
        btnMode.setOnClickListener {
            separateMode = !separateMode
            updateModeButton()
        }

        updateModeButton()

        if (Build.VERSION.SDK_INT < 29 && Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }

        handleIntent(intent)
        refreshList()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        refreshList()
    }

    private fun updateModeButton() {
        btnMode.text = if (separateMode) getString(R.string.mode_separate) else getString(R.string.mode_combined)
    }

    private fun createWorkerWebView(): WebView {
        val wv = WebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.blockNetworkLoads = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.loadWithOverviewMode = false
        s.useWideViewPort = false
        s.textZoom = 100
        wv.setBackgroundColor(0xFFFFFFFF.toInt())
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun done() {
                doneLatch.countDown()
            }

            @JavascriptInterface
            fun getB64(): String = pendingB64 ?: ""

            @JavascriptInterface
            fun reportError(msg: String) {
                jsError = msg
            }
        }, "converterBridge")
        val root = findViewById<FrameLayout>(R.id.root)
        val lp = FrameLayout.LayoutParams(794, ViewGroup.LayoutParams.WRAP_CONTENT)
        wv.layoutParams = lp
        wv.translationX = -20000f
        wv.translationY = -20000f
        root.addView(wv, lp)
        return wv
    }

    private fun destroyWorkerWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || converting) return
        val action = intent.action ?: return
        val uris = ArrayList<Uri>()
        if (action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else if (action == Intent.ACTION_VIEW || action == Intent.ACTION_MAIN) {
            intent.data?.let { uris.add(it) }
        }
        for (uri in uris) {
            if (uri == null) continue
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val p = describe(uri) ?: continue
            if (picks.none { it.uri == p.uri }) picks.add(p)
        }
    }

    private fun describe(uri: Uri): Picked? {
        val mime = contentResolver.getType(uri)
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            ), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                }
            }
        } catch (_: Exception) {
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment ?: DocumentFile.fromSingleUri(this, uri)?.name ?: "file"
        val displayName = name ?: "file"
        val raw = displayName.substringBeforeLast('.').let { n -> displayName.substringAfterLast('.', "bin") }
        val ext = raw.lowercase()
        val finalExt = when {
            ext.length in 1..8 && ext.all { it.isLetterOrDigit() } -> ext
            mime == "application/pdf" -> "pdf"
            mime == "text/plain" -> "txt"
            mime == "text/markdown" -> "md"
            mime == "text/html" -> "html"
            else -> when (mime) {
                "application/pdf" -> "pdf"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "application/msword" -> "doc"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
                "application/vnd.ms-powerpoint" -> "ppt"
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "bin"
            }
        }
        var size = 0L
        try {
            size = DocumentFile.fromSingleUri(this, uri)?.length() ?: 0L
        } catch (_: Exception) {
        }
        if (size <= 0L) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
            } catch (_: Exception) {
            }
        }
        return Picked(displayName, uri, finalExt, size)
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        btnConvert.isEnabled = picks.isNotEmpty() && !converting
    }

    private fun pickFiles() {
        if (converting) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "image/*",
                "text/plain",
                "text/html"
            ))
        }
        startActivityForResult(i, 200)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            if (data.clipData != null) {
                for (j in 0 until data.clipData!!.itemCount) {
                    data.clipData!!.getItemAt(j).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                val p = describe(uri) ?: continue
                if (picks.none { it.uri == p.uri }) picks.add(p)
            }
            refreshList()
        } else if (requestCode == 300 && resultCode == Activity.RESULT_OK && data != null) {
            val dest = data.data
            val file = pendingSave
            val name = pendingName
            pendingSave = null
            pendingName = null
            if (dest != null && file != null) {
                try {
                    contentResolver.openOutputStream(dest)?.use { os ->
                        file.inputStream().copyTo(os)
                    }
                    showResult(name ?: "apdf.pdf", dest, file)
                } catch (e: Exception) {
                    AlertDialog.Builder(this)
                        .setTitle("Save failed")
                        .setMessage(e.message ?: "Could not write to the chosen location.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        } else if (requestCode == 400 && resultCode == Activity.RESULT_OK && data != null) {
            val treeUri = data.data
            val pending = pendingSaves
            pendingSaves = emptyList()
            if (treeUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val tree = DocumentFile.fromTreeUri(this, treeUri)
                var saved = 0
                if (tree != null) {
                    for ((file, name) in pending) {
                        val doc = tree.createFile("application/pdf", name.removeSuffix(".pdf"))
                        if (doc != null) {
                            contentResolver.openOutputStream(doc.uri)?.use { os ->
                                file.inputStream().copyTo(os)
                            }
                            saved++
                        }
                    }
                }
                if (saved > 0 && pending.isNotEmpty()) {
                    showResult("$saved PDF(s) saved", treeUri, pending.first().first)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Save failed")
                        .setMessage("Could not write the PDF(s) to the chosen folder.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun startConversion() {
        if (picks.isEmpty() || converting) return
        converting = true
        refreshList()
        val progress = ProgressDialog(this).apply {
            setMessage("Converting… please wait")
            setCancelable(false)
            setIndeterminate(true)
        }
        progress.show()

        val snapshot = ArrayList(picks)
        Thread {
            try {
                if (separateMode) {
                    val results = ArrayList<Pair<File, String>>()
                    snapshot.forEachIndexed { idx, pick ->
                        val src = copyToCache(pick, idx)
                        val pdf = convertOne(src, pick.ext)
                        val finalPdf = ensureUnder8mb(pdf)
                        results.add(finalPdf to singleName(pick, idx))
                    }
                    mainHandler.post {
                        progress.dismiss()
                        launchSaveToDirectory(results)
                    }
                } else {
                    val pdfs = ArrayList<File>()
                    snapshot.forEachIndexed { idx, pick ->
                        val src = copyToCache(pick, idx)
                        pdfs.add(convertOne(src, pick.ext))
                    }
                    val merged = if (pdfs.size == 1) pdfs[0] else mergePdfs(pdfs)
                    val finalFile = ensureUnder8mb(merged)
                    val outName = outputName(snapshot)
                    mainHandler.post {
                        progress.dismiss()
                        launchSaveAs(finalFile, outName)
                    }
                }
            } catch (t: Throwable) {
                val trace = t.stackTrace.take(8).joinToString("\n") { "  at $it" }
                val msg = "${t.javaClass.simpleName}: ${t.message}\n\n$trace"
                mainHandler.post {
                    progress.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Conversion failed")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                converting = false
                mainHandler.post { refreshList() }
            }
        }.start()

        mainHandler.postDelayed({
            if (converting) {
                converting = false
                progress.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Conversion failed")
                    .setMessage("Timeout: conversion took too long.")
                    .setPositiveButton("OK", null)
                    .show()
                refreshList()
            }
        }, 4 * 60 * 1000L)
    }

    private fun singleName(pick: Picked, idx: Int): String {
        val base = pick.name
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "document" }
        val ts = System.currentTimeMillis() % 100000
        return "${base}_${idx}_$ts.pdf"
    }

    private fun outputName(snapshot: List<Picked>): String {
        val base = snapshot.first().name
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "document" }
        val ts = System.currentTimeMillis() % 100000
        return if (snapshot.size == 1) "${base}_$ts.pdf" else "${base}_${snapshot.size}files_$ts.pdf"
    }

    private fun copyToCache(pick: Picked, idx: Int): File {
        val f = File(cacheDir, "input_${idx}_${pick.name}").let {
            if (it.exists()) it else it
        }
        try {
            contentResolver.openInputStream(pick.uri)?.use { ins ->
                FileOutputStream(f).use { outs -> ins.copyTo(outs) }
            }
        } catch (e: Exception) {
            throw IOException("Could not read ${pick.name}: ${e.message}")
        }
        return f
    }

    private fun convertOne(file: File, ext: String): File {
        return when (ext) {
            "pdf" -> file
            "docx" -> runJsConversion(docxWorkerHtml(), file, "docx", 60_000)
            "doc" -> throw IOException("'$ext' is the old Word format. Re-save the document as .docx and try again.")
            "pptx" -> runJsConversion(pptxWorkerHtml(), file, "pptx", 90_000)
            "ppt" -> throw IOException("'$ext' is the old PowerPoint format. Re-save the presentation as .pptx and try again.")
            "png", "jpg", "jpeg", "webp", "bmp", "gif" -> imageToPdf(file, ext)
            "txt", "md", "markdown", "html", "htm" -> runJsConversion(textWorkerHtml(file, ext), file, "text", 30_000)
            else -> throw IOException("Unsupported file type '.$ext'.")
        }
    }

    // ---------- WebView / JS conversion ----------

    private fun runJsConversion(workerHtml: String, file: File, label: String, timeoutMs: Long): File {
        val bytes = file.readBytes()
        if (bytes.size > 45 * 1024 * 1024) {
            throw IOException("$label is too large for on-device rendering (max ~45 MB).")
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val wv = onMain { createWorkerWebView() }
        try {
            doneLatch = CountDownLatch(1)
            jsError = null
            pendingB64 = b64
            var started = false
            val runConvert = Runnable {
                if (!started) {
                    started = true
                    wv.evaluateJavascript("window.start()", null)
                }
            }
            mainHandler.post {
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.post(runConvert)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                }
                wv.loadDataWithBaseURL(null, workerHtml, "text/html", "utf-8", null)
            }
            mainHandler.postDelayed(runConvert, 15_000)

            if (!doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("Rendering '$label' timed out.")
            }
            jsError?.let { throw IOException("$label conversion error: $it") }

            Thread.sleep(700)
            onMain { layoutForPrint(wv) }
            return printWebViewToPdf(wv, uniquePdf(label))
        } finally {
            pendingB64 = null
            mainHandler.post { destroyWorkerWebView(wv) }
        }
    }

    private fun layoutForPrint(wv: WebView) {
        val w = 794
        wv.layoutParams = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        wv.requestLayout()
        val specW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val specH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        wv.measure(specW, specH)
        val h = wv.measuredHeight
        wv.layout(0, 0, w, h)
    }

    private fun printWebViewToPdf(wv: WebView, outFile: File): File {
        if (outFile.exists()) outFile.delete()
        val adapter = onMain { wv.createPrintDocumentAdapter("document") }
        val ok = PdfPrint().print(adapter, outFile)
        return if (ok) outFile else {
            if (outFile.exists()) outFile.delete()
            onMain { rasterizeWebViewToPdf(wv, outFile) }
        }
    }

    private fun rasterizeWebViewToPdf(wv: WebView, outFile: File): File {
        val pageW = 794
        val pageH = 1123
        val scale = 2.0f
        val bmpW = (pageW * scale).toInt()
        val bmpH = (pageH * scale).toInt()
        val totalH = max(wv.measuredHeight, pageH)
        val numPages = (totalH + pageH - 1) / pageH
        val pagePts = PDRectangle(595.28f, 841.89f)
        val doc = PDDocument()
        try {
            for (i in 0 until numPages) {
                val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.scale(scale, scale)
                canvas.translate(0f, -(i * pageH).toFloat())
                canvas.drawColor(android.graphics.Color.WHITE)
                wv.draw(canvas)
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                bmp.recycle()
                val page = PDPage(pagePts)
                doc.addPage(page)
                val img = PDImageXObject.createFromByteArray(doc, bos.toByteArray(), "p$i.jpg")
                val cs = PDPageContentStream(doc, page)
                cs.drawImage(img, 0f, 0f, 595.28f, 841.89f)
                cs.close()
            }
            doc.save(outFile)
            return outFile
        } finally {
            try {
                doc.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var res: T? = null
        var err: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                res = block()
            } catch (t: Throwable) {
                err = t
            }
            latch.countDown()
        }
        latch.await()
        if (err != null) throw err!!
        return res!!
    }

    // ---------- Worker HTML templates ----------

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private val mammothJs by lazy { readAsset("js/mammoth.min.js") }
    private val jqueryJs by lazy { readAsset("js/jquery.min.js") }
    private val jszipJs by lazy { readAsset("js/jszip.min.js") }
    private val d3Js by lazy { readAsset("js/d3.min.js") }
    private val dimpleJs by lazy { readAsset("js/dimple.min.js") }
    private val pptxJs by lazy { readAsset("js/pptx2html.min.js") }

    private val DOCX_TEMPLATE = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <style>
        @page { size: A4; margin: 12mm; }
        html, body { margin: 0; padding: 0; }
        body { font-family: Calibri, 'Segoe UI', Arial, sans-serif; font-size: 12pt; color: #000; }
        img { max-width: 100%; }
        table { border-collapse: collapse; }
        </style></head><body>
        <script>
        window.done = false;
        window.finish = function (err) {
          if (err) { window.error = String(err); try { converterBridge.reportError(String(err)); } catch (e) {} }
          window.done = true;
          try { converterBridge.done(); } catch (e) {}
        };
        function b64ToBytes(b) { var bin = atob(b), len = bin.length, arr = new Uint8Array(len); for (var i = 0; i < len; i++) arr[i] = bin.charCodeAt(i); return arr; }
        window.convert = function () {
          try {
            var b64 = converterBridge.getB64();
            mammoth.convertToHtml({ arrayBuffer: b64ToBytes(b64).buffer }).then(function (r) {
              document.body.innerHTML = r.value;
              window.finish(null);
            }, function (err) { window.finish(err); });
          } catch (e) { window.finish(e); }
        };
        window.start = function () {
          function attempt() {
            if (window.mammoth) { window.convert(); } else { setTimeout(attempt, 150); }
          }
          attempt();
        };
        </script>
        <script>__MAMMOTH__</script>
        </body></html>
    """.trimIndent()

    private val PPTX_TEMPLATE = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <style>
        @page { size: A4; margin: 0; }
        html, body { margin: 0; padding: 0; background: #fff; }
        #host { width: 794px; }
        </style></head><body>
        <div id="host"></div>
        <script>
        window.done = false;
        window.finish = function (err) {
          if (err) { window.error = String(err); try { converterBridge.reportError(String(err)); } catch (e) {} }
          window.done = true;
          try { converterBridge.done(); } catch (e) {}
        };
        function b64ToBytes(b) { var bin = atob(b), len = bin.length, arr = new Uint8Array(len); for (var i = 0; i < len; i++) arr[i] = bin.charCodeAt(i); return arr; }
        window.convert = function () {
          try {
            var host = document.getElementById('host');
            host.innerHTML = '';
            pptx2html(b64ToBytes(converterBridge.getB64()).buffer, '#host', null).then(function (time) {
              window.fitSlides();
              window.finish(null);
            }, function (err) { window.finish(err); });
          } catch (e) { window.finish(e); }
        };
        window.start = function () {
          function attempt() {
            if (window.pptx2html) { window.convert(); } else { setTimeout(attempt, 150); }
          }
          attempt();
        };
        window.fitSlides = function () {
          var PW = 794, PH = 1123;
          var host = document.getElementById('host');
          var wrapper = host.querySelector('.pptx-wrapper');
          if (wrapper) wrapper.style.transform = 'none';
          var slides = host.querySelectorAll('section');
          for (var i = 0; i < slides.length; i++) {
            var s = slides[i];
            var w = parseFloat(s.style.width) || 960, h = parseFloat(s.style.height) || 540;
            var scale = Math.min(PW / w, PH / h);
            s.style.transformOrigin = '0 0';
            s.style.transform = 'scale(' + scale + ')';
            s.style.width = (w * scale) + 'px';
            s.style.height = (h * scale) + 'px';
            s.style.margin = '0 0 14px 0';
            s.style.pageBreakAfter = 'always';
          }
        };
        </script>
        <script>__JQUERY__</script>
        <script>__JSZIP__</script>
        <script>__D3__</script>
        <script>__DIMPLE__</script>
        <script>__PPTX__</script>
        </body></html>
    """.trimIndent()

    private fun docxWorkerHtml(): String = DOCX_TEMPLATE.replace("__MAMMOTH__", mammothJs)

    private fun pptxWorkerHtml(): String = PPTX_TEMPLATE
        .replace("__JQUERY__", jqueryJs)
        .replace("__JSZIP__", jszipJs)
        .replace("__D3__", d3Js)
        .replace("__DIMPLE__", dimpleJs)
        .replace("__PPTX__", pptxJs)

    private fun textWorkerHtml(file: File, ext: String): String {
        val raw = file.readText(Charsets.UTF_8)
        val bodyHtml = when (ext) {
            "html", "htm" -> raw
            else -> raw
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .let { "<pre style=\"white-space:pre-wrap;font-family:Consolas,monospace;font-size:11pt\">$it</pre>" }
        }
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>@page { size: A4; margin: 14mm; } html,body{margin:0;padding:0}</style>
            </head><body>$bodyHtml
            <script>window.start = function () { try { converterBridge.done(); } catch (e) {} };</script>
            </body></html>
        """.trimIndent()
    }

    // ---------- Image -> PDF ----------

    private fun imageToPdf(file: File, ext: String): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Could not decode image ${file.name}.")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: throw IOException("Could not decode image ${file.name}.")
        val rotated = fixRotation(file.path, bmp, bounds.outWidth, bounds.outHeight)
        val pageW = rotated.first.toFloat()
        val pageH = rotated.second.toFloat()
        val doc = PDDocument()
        try {
            val bos = java.io.ByteArrayOutputStream()
            rotated.third.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            val img = PDImageXObject.createFromByteArray(doc, bos.toByteArray(), "img.jpg")
            val page = PDPage(PDRectangle(pageW, pageH))
            doc.addPage(page)
            val cs = PDPageContentStream(doc, page)
            cs.drawImage(img, 0f, 0f, pageW, pageH)
            cs.close()
            val out = uniquePdf("img")
            doc.save(out)
            return out
        } finally {
            doc.close()
            if (!rotated.third.isRecycled) rotated.third.recycle()
            if (!bmp.isRecycled && bmp !== rotated.third) bmp.recycle()
        }
    }

    private fun fixRotation(path: String, bmp: Bitmap, w: Int, h: Int): Triple<Int, Int, Bitmap> {
        var rotation = 0
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                rotation = android.media.ExifInterface(path)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) {
            }
        }
        return when (rotation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> Triple(h, w, rotate(bmp, 90f))
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> Triple(w, h, rotate(bmp, 180f))
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> Triple(h, w, rotate(bmp, 270f))
            else -> Triple(w, h, bmp)
        }
    }

    private fun rotate(bmp: Bitmap, deg: Float): Bitmap {
        val m = Matrix()
        m.postRotate(deg)
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (!bmp.isRecycled) bmp.recycle()
        return out
    }

    // ---------- PDF merge / compress ----------

    private fun mergePdfs(files: List<File>): File {
        val out = uniquePdf("merged")
        val merger = PDFMergerUtility()
        files.forEach { merger.addSource(it) }
        merger.setDestinationFileName(out.absolutePath)
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        return out
    }

    private fun compressPdf(input: File, dpi: Int, quality: Int): File {
        val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val doc = PDDocument()
        try {
            val scale = dpi / 72f
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                val bw = max(1, (pw * scale).toInt())
                val bh = max(1, (ph * scale).toInt())
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val m = Matrix()
                m.postScale(scale, scale)
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                page.close()
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bmp.recycle()
                val pdPage = PDPage(PDRectangle(pw, ph))
                doc.addPage(pdPage)
                val img = PDImageXObject.createFromByteArray(doc, bos.toByteArray(), "p$i.jpg")
                val cs = PDPageContentStream(doc, pdPage)
                cs.drawImage(img, 0f, 0f, pw, ph)
                cs.close()
            }
            val out = uniquePdf("compressed")
            doc.save(out)
            return out
        } finally {
            try {
                renderer.close()
            } catch (_: Exception) {
            }
            try {
                pfd.close()
            } catch (_: Exception) {
            }
            try {
                doc.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureUnder8mb(input: File): File {
        val limit = 8L * 1024 * 1024
        if (input.length() <= limit) return input
        var best = input
        var bestLen = input.length()
        for ((dpi, q) in arrayOf(200 to 80, 150 to 75, 120 to 70, 100 to 62, 85 to 52)) {
            val f = compressPdf(input, dpi, q)
            if (f.length() < bestLen) {
                if (best !== input) best.delete()
                best = f
                bestLen = f.length()
            } else {
                f.delete()
            }
            if (bestLen <= limit) break
        }
        if (best !== input) input.delete()
        return best
    }

    // ---------- Saving & sharing ----------

    private var uniqueCounter = 0
    private fun uniquePdf(prefix: String): File =
        File(cacheDir, "${prefix}_${System.currentTimeMillis()}_${uniqueCounter++}.pdf")

    private fun launchSaveAs(finalFile: File, name: String) {
        pendingSave = finalFile
        pendingName = name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, 300)
    }

    private fun launchSaveToDirectory(files: List<Pair<File, String>>) {
        pendingSaves = files
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(i, 400)
    }

    private fun showResult(name: String, uri: Uri?, file: File) {
        AlertDialog.Builder(this)
            .setTitle("Done")
            .setMessage("Saved $name")
            .setItems(arrayOf("Share", "Open", "OK")) { _, which ->
                when (which) {
                    0 -> shareFile(uri, file)
                    1 -> openFile(uri, file)
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun shareableUri(uri: Uri?, file: File): Uri =
        if (uri != null && uri.scheme == "content") uri
        else FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun shareFile(uri: Uri?, file: File) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, shareableUri(uri, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share PDF"))
    }

    private fun openFile(uri: Uri?, file: File) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareableUri(uri, file), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "No PDF viewer found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        if (kb < 1) return "$bytes B"
        val mb = kb / 1024.0
        return if (mb < 1) String.format("%.0f KB", kb) else String.format("%.1f MB", mb)
    }
}