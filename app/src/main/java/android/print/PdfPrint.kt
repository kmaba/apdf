package android.print

import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PdfPrint {

    private var pfd: ParcelFileDescriptor? = null

    /**
     * Drives a [PrintDocumentAdapter] (e.g. one from a WebView) through a full
     * layout + write cycle, capturing the produced PDF into [outFile].
     *
     * Lives in the `android.print` package on purpose: the abstract callback
     * classes below have package-private constructors that app code normally
     * cannot subclass.
     */
    fun print(printAdapter: PrintDocumentAdapter, outFile: File): Boolean {
        if (outFile.exists()) outFile.delete()
        pfd = ParcelFileDescriptor.open(
            outFile,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        val latch = CountDownLatch(1)
        var ok = false
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("hi", "hi", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
        printAdapter.onLayout(
            null, attrs, null,
            object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    printAdapter.onWrite(
                        arrayOf(PageRange.ALL_PAGES),
                        pfd,
                        null,
                        object : PrintDocumentAdapter.WriteResultCallback() {
                            override fun onWriteFinished(pages: Array<out PageRange>) {
                                ok = true
                                closePfd()
                                latch.countDown()
                            }

                            override fun onWriteFailed(error: CharSequence?) {
                                closePfd()
                                latch.countDown()
                            }
                        }
                    )
                }

                override fun onLayoutCancelled() {
                    closePfd()
                    latch.countDown()
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    closePfd()
                    latch.countDown()
                }
            },
            null
        )
        val done = try {
            latch.await(120, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        closePfd()
        return done && ok && outFile.exists() && outFile.length() > 0
    }

    private fun closePfd() {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }
}