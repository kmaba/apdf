# Changelog

## v1.3.0 (in progress)

### Crash fixes
- **WebView print crash**: `WebView.createPrintDocumentAdapter()` could not be
  driven manually on Android 16 — its `onLayout`/`onWrite` aborted with a native
  `SIGTRAP` from a background thread, and deadlocked (ANR) on the UI thread.
  Replaced the manual print adapter with a raster approach: render the WebView
  to bitmaps and build the PDF with PDFBox.
- Fixed the original `NullPointerException` in `ensureWebView` by creating the
  WebView once in `onCreate` on the main thread (host container can never be null).
- Hardened `onDestroy` (detach WebView before `destroy()`), the `onPageFinished`
  callback, and made `converting` `@Volatile`.

### Conversion changes
- **Single conversion path** (no native fallback). Removed `NativeConverters.kt`
  and its test.
- **No 8 MB cap**: removed `compressPdf`/`ensureUnder8mb`; PDFs keep full quality.
- **docx**: switched from mammoth to `docx-preview` (docxjs) for Word-faithful
  layout (fonts, colors, tables, spacing). Bundled
  `assets/js/docx-preview.min.js`, removed `mammoth.min.js`.
- **pptx**: slides now render landscape (1123×794) instead of squashed portrait.
- PDFs pass through unmodified; images → PDFBox; text/html → WebView.

### UI
- Completion dialog now shows **Open** / **Share** / **Done** buttons.

### Still open (known issues)
- WebView raster capture is unreliable for multi-page documents: the Chromium
  compositor only rasterizes ~1 screen-height of content, so pages beyond the
  first couple render blank. Current attempt briefly brings the WebView into
  view while scrolling each page, but this still isn't producing correct output
  on all devices. A faithful offline docx/pptx renderer remains unsolved.

### File sharing / delivery (from this session)
- `sendit.sh` is a remote file host: `curl sendit.sh -T <file>` returns a
  download link.
- APK (~11 MB) is too large for the Discord webhook (8 MB limit); send the
  sendit.sh link instead.
