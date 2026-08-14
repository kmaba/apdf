package link.kmaba.apdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Self-contained Office-to-PDF converter. No WebView, no system rendering:
 * .docx / .pptx are parsed from their OOXML zip + XML directly, .txt/.md/.html
 * are read as text, and everything is laid out onto PDF pages with PDFBox.
 */
class NativeConverters(private val fontProvider: () -> InputStream) {

    companion object {
        private const val A4W = 595.28f
        private const val A4H = 841.89f
        private const val MARGIN = 40f

        private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        private const val PAGE_BREAK_CHAR = '\u000C'

        fun wrap(text: String, font: PDType0Font, size: Float, maxW: Float): List<String> {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            var cur = ""
            for (w in words) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (font.getStringWidth(t) / 1000f * size <= maxW || cur.isEmpty()) cur = t
                else {
                    lines.add(cur)
                    cur = w
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            return lines
        }
    }

    // ---------------------------------------------------------------- public

    fun textToPdf(file: File, out: File): File {
        val raw = file.readText(Charsets.UTF_8)
        val paragraphs = raw
            .replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { it.trim().replace('\n', ' ') }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) {
            renderParagraphs(out, listOf("(empty document)"))
            return out
        }
        renderParagraphs(out, paragraphs)
        return out
    }

    fun htmlToPdf(file: File, out: File): File {
        val html = file.readText(Charsets.UTF_8)
        val text = htmlToText(html)
        val paragraphs = text
            .split(Regex("\n\\s*\n"))
            .map { it.trim().replace('\n', ' ') }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) {
            renderParagraphs(out, listOf("(empty document)"))
            return out
        }
        renderParagraphs(out, paragraphs)
        return out
    }

    fun docxToPdf(file: File, out: File): File {
        ZipFile(file).use { zip ->
            val docEntry = zip.getEntry("word/document.xml")
                ?: throw IOException("Not a valid .docx file (missing document.xml).")
            val rels = readRels(zip, "word/_rels/document.xml.rels")
            val root = parseXml(zip.getInputStream(docEntry))
            val body = first(root, "body") ?: root

            // page setup from the last section properties (twips; 1pt = 20 twips)
            var pageW = A4W
            var pageH = A4H
            var marg = MARGIN
            findLast(body, "sectPr")?.let { sectPr ->
                first(sectPr, "pgSz")?.let { sz ->
                    val w = sz.attr("w")?.toFloatOrNull()
                    val h = sz.attr("h")?.toFloatOrNull()
                    if (w != null && h != null) { pageW = w / 20f; pageH = h / 20f }
                }
                first(sectPr, "pgMar")?.let { m ->
                    m.attr("left")?.toFloatOrNull()?.let { marg = max(18f, it / 20f) }
                }
            }

            val bullets = readNumbering(zip)
            val blocks = mutableListOf<Block>()
            for (child in body.children) {
                when (child.name) {
                    "p" -> blocks.add(parseDocxParagraph(child, bullets))
                    "tbl" -> blocks.add(parseDocxTable(child))
                    "sdt" -> child.children.forEach { c ->
                        when (c.name) {
                            "p" -> blocks.add(parseDocxParagraph(c, bullets))
                            "tbl" -> blocks.add(parseDocxTable(c))
                        }
                    }
                }
            }
            renderDocxBlocks(out, blocks, zip, rels, pageW, pageH, marg)
        }
        return out
    }

    fun pptxToPdf(file: File, out: File): File {
        ZipFile(file).use { zip ->
            val presEntry = zip.getEntry("ppt/presentation.xml")
                ?: throw IOException("Not a valid .pptx file (missing presentation.xml).")
            val pres = parseXml(zip.getInputStream(presEntry))
            val sldSz = first(pres, "sldSz")
            val slideW = attrInt(sldSz, "cx") ?: 12192000
            val slideH = attrInt(sldSz, "cy") ?: 6858000

            val rels = readRels(zip, "ppt/_rels/presentation.xml.rels")
            val slidePaths = mutableListOf<String>()
            first(pres, "sldIdLst")?.children?.forEach { id ->
                val rid = attrNs(id, NS_R, "id")
                if (rid != null) rels[rid]?.let { slidePaths.add(it) }
            }
            if (slidePaths.isEmpty()) {
                var i = 1
                while (true) {
                    val name = "ppt/slides/slide$i.xml"
                    if (zip.getEntry(name) != null) slidePaths.add("slides/slide$i.xml")
                    else if (i > 1) break
                    i++
                    if (i > 500) break
                }
            }

            val doc = PDDocument()
            try {
                val font = loadFont(doc)
                val slideWPt = slideW / 12700f
                val slideHPt = slideH / 12700f
                val scale = min((A4W - 2 * MARGIN) / slideWPt, (A4H - 2 * MARGIN) / slideHPt)
                val offX = (A4W - slideWPt * scale) / 2f
                val offY = (A4H - slideHPt * scale) / 2f
                for (path in slidePaths) {
                    val fullPath = if (path.startsWith("/")) path.trimStart('/') else "ppt/$path"
                    val entry = zip.getEntry(fullPath) ?: continue
                    val slideRels = readRels(zip, relPathOf(fullPath))
                    val slide = parseXml(zip.getInputStream(entry))
                    val page = PDPage(PDRectangle(A4W, A4H))
                    doc.addPage(page)
                    val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    try {
                        renderSlide(doc, cs, slide, zip, slideRels, scale, offX, offY, font)
                    } finally {
                        cs.close()
                    }
                }
                doc.save(out)
            } finally {
                doc.close()
            }
        }
        return out
    }

    // ---------------------------------------------------------------- render

    private fun renderParagraphs(out: File, paragraphs: List<String>) {
        createDoc(out, A4W, A4H, MARGIN) { w, _ ->
            for (p in paragraphs) w.drawParagraph(p, 11f, Align.LEFT, spaceAfter = 7f)
        }
    }

    private fun renderDocxBlocks(
        out: File, blocks: List<Block>, zip: ZipFile, rels: Map<String, String>,
        pageW: Float, pageH: Float, marg: Float
    ) {
        createDoc(out, pageW, pageH, marg) { w, _ ->
            for (block in blocks) {
                when (block) {
                    is DParagraph -> {
                        if (block.pageBreakBefore) w.newPageNow()
                        var bold = false
                        var italic = false
                        var size = 11f
                        var color: Int? = null
                        var underline = false
                        val sb = StringBuilder()
                        for (run in block.runs) {
                            if (run.image != null) continue
                            if (sb.isEmpty()) {
                                bold = run.bold
                                italic = run.italic
                                size = run.size ?: 11f
                                color = run.color
                                underline = run.underline
                            }
                            sb.append(run.text)
                        }
                        if (sb.isNotEmpty()) {
                            val bullet = block.bullet
                            if (bullet != null) {
                                w.drawParagraph("  $bullet ${sb}", size, parseAlign(block.align),
                                    bold = bold, color = color, underline = underline, spaceAfter = 3f, indent = 14f)
                            } else {
                                w.drawParagraph(sb.toString(), size, parseAlign(block.align),
                                    bold = bold, color = color, underline = underline, spaceAfter = 6f)
                            }
                        }
                        for (run in block.runs) {
                            if (run.image == null) continue
                            val target = rels[run.image] ?: continue
                            val entry = zip.getEntry(resolveTarget("word", target)) ?: continue
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            drawImageBlock(w, w.doc, bytes)
                        }
                    }
                    is DTable -> {
                        for (row in block.rows) {
                            w.drawParagraph(row.joinToString("  |  "), 10.5f, Align.LEFT, spaceAfter = 3f)
                        }
                        w.drawParagraph("", 6f, Align.LEFT, spaceAfter = 4f)
                    }
                }
            }
        }
    }

    private fun drawImageBlock(w: PdfWriter, doc: PDDocument, bytes: ByteArray) {
        try {
            val img = PDImageXObject.createFromByteArray(doc, bytes, "img")
            val iw = img.width.toFloat()
            val ih = img.height.toFloat()
            val s = min(1f, min(w.contentW / iw, w.contentW / ih))
            val dw = iw * s
            val dh = ih * s
            w.ensure(dh + 12f)
            w.cs.drawImage(img, (w.pageW - dw) / 2f, w.y - dh, dw, dh)
            w.y -= dh + 12f
        } catch (_: Exception) {
            // unsupported image type - skip
        }
    }

    private fun renderSlide(
        doc: PDDocument,
        cs: PDPageContentStream,
        slide: Node,
        zip: ZipFile,
        rels: Map<String, String>,
        scale: Float, offX: Float, offY: Float,
        font: PDType0Font
    ) {
        val tree = first(first(slide, "cSld"), "spTree") ?: return
        renderShapes(doc, cs, tree, zip, rels, scale, offX, offY, font)
    }

    private fun renderShapes(
        doc: PDDocument,
        cs: PDPageContentStream,
        node: Node,
        zip: ZipFile,
        rels: Map<String, String>,
        scale: Float, offX: Float, offY: Float,
        font: PDType0Font
    ) {
        for (shape in node.children) {
            when (shape.name) {
                "sp", "pic", "graphicFrame", "cxnSp" -> renderShape(doc, cs, shape, zip, rels, scale, offX, offY, font)
                "grpSp" -> renderShapes(doc, cs, shape, zip, rels, scale, offX, offY, font)
            }
        }
    }

    private fun renderShape(
        doc: PDDocument,
        cs: PDPageContentStream,
        shape: Node,
        zip: ZipFile,
        rels: Map<String, String>,
        scale: Float, offX: Float, offY: Float,
        font: PDType0Font
    ) {
        val (x, y, cx, cy) = anchorOf(shape)
        if (shape.name == "pic") {
            val rid = findBlip(shape)?.let { attrNs(it, NS_R, "embed") }
            val target = rid?.let { rels[it] }
            if (target != null) {
                try {
                    val entry = zip.getEntry(resolveTarget("ppt/slides", target))
                    if (entry != null) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val img = PDImageXObject.createFromByteArray(doc, bytes, "img")
                        val dx = (cx ?: 0) * scale
                        val dy = (cy ?: 0) * scale
                        cs.drawImage(img, offX + (x ?: 0) * scale, offY + ((y ?: 0) + (cy ?: 0)) * scale, dx, dy)
                    }
                } catch (_: Exception) {
                }
            }
            return
        }

        if (x == null || y == null || cx == null || cy == null) return
        val txBody = descendant(shape, "txBody") ?: return
        var fontSize = 18f
        var bold = false
        var color: Int? = null
        var align = Align.LEFT
        val sb = StringBuilder()
        for (para in txBody.children.filter { it.name == "p" }) {
            first(para, "pPr")?.let { pPr ->
                first(pPr, "algn")?.let { a ->
                    when (a.attr("val")) {
                        "ctr" -> align = Align.CENTER
                        "r" -> align = Align.RIGHT
                    }
                }
            }
            for (r in para.children.filter { it.name == "r" }) {
                first(r, "rPr")?.let { rPr ->
                    rPr.attr("sz")?.toFloatOrNull()?.let { sz -> fontSize = sz / 100f }
                    if (rPr.attr("b") == "1" || rPr.attr("b") == "true") bold = true
                    color = solidColor(rPr) ?: color
                }
                sb.append(r.children.firstOrNull { it.name == "t" }?.text ?: "")
            }
            sb.append('\n')
        }
        drawTextInBox(cs, sb.toString().trimEnd('\n'), x, y, cx, cy, fontSize, bold, color, align, scale, offX, offY, font)
    }

    private fun drawTextInBox(
        cs: PDPageContentStream,
        text: String,
        x: Int, y: Int, cx: Int, cy: Int,
        fontSize: Float, bold: Boolean,
        color: Int?, align: Align,
        scale: Float, offX: Float, offY: Float,
        font: PDType0Font
    ) {
        if (text.isEmpty()) return
        val size = max(8f, fontSize * scale)
        val maxW = cx * scale
        val lineH = size * 1.2f
        val lines = wrap(text, font, size, maxW)
        var baseline = offY + (y + cy) * scale - size * 0.8f
        cs.setFont(font, size)
        if (bold) {
            cs.setRenderingMode(RenderingMode.FILL_STROKE)
            cs.setLineWidth(0.3f)
        }
        if (color != null) cs.setNonStrokingColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        for (line in lines) {
            if (baseline < offY + y * scale) break
            val lw = font.getStringWidth(line) / 1000f * size
            val sx = when (align) {
                Align.CENTER -> offX + x * scale + (maxW - lw) / 2f
                Align.RIGHT -> offX + x * scale + (maxW - lw)
                else -> offX + x * scale
            }
            try {
                cs.beginText()
                cs.newLineAtOffset(sx, baseline)
                cs.showText(line)
                cs.endText()
            } catch (_: Exception) {
            }
            baseline -= lineH
        }
        if (bold) cs.setRenderingMode(RenderingMode.FILL)
        if (color != null) cs.setNonStrokingColor(0, 0, 0)
    }

    // ---------------------------------------------------------------- parse

    private fun parseDocxParagraph(node: Node, bullets: Map<String, String>): DParagraph {
        val pPr = first(node, "pPr")
        val align = first(pPr, "jc")?.attr("val")
        var bullet: String? = null
        var pageBreak = false
        pPr?.let { p ->
            first(p, "numPr")?.let { numPr ->
                val ilvl = attrInt(first(numPr, "ilvl"), "val") ?: 0
                val numId = attrInt(first(numPr, "numId"), "val")?.toString()
                val fmt = numId?.let { bullets[it] } ?: "bullet"
                bullet = if (numId != null && fmt.startsWith("decimal")) {
                    "${nextListNumber(numId, ilvl)}."
                } else {
                    "•"
                }
            }
            if (first(p, "pageBreakBefore") != null) pageBreak = true
        }
        val runs = mutableListOf<DRun>()
        for (child in node.children) {
            when (child.name) {
                "r" -> runs.add(parseDocxRun(child))
                "hyperlink" -> child.children.filter { it.name == "r" }.forEach { runs.add(parseDocxRun(it)) }
            }
        }
        return DParagraph(runs, align, bullet, pageBreak)
    }

    private val listCounters = mutableMapOf<String, Int>()

    private fun nextListNumber(numId: String, ilvl: Int): Int {
        val key = "$numId:$ilvl"
        val next = (listCounters[key] ?: 0) + 1
        listCounters[key] = next
        // reset deeper levels
        listCounters.keys
            .filter { it.startsWith("$numId:") && (it.substringAfterLast(':').toIntOrNull() ?: 0) > ilvl }
            .forEach { listCounters[it] = 0 }
        return next
    }

    private fun readNumbering(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("word/numbering.xml") ?: return emptyMap()
        return try {
            val root = parseXml(zip.getInputStream(entry))
            val abs = mutableMapOf<Int, String>()
            for (absNum in root.children.filter { it.name == "abstractNum" }) {
                val absId = attrInt(absNum, "abstractNumId") ?: continue
                val lvl = absNum.children.filter { it.name == "lvl" }.firstOrNull { attrInt(it, "ilvl") == 0 }
                val fmt = lvl?.let { first(it, "numFmt") }?.attr("val")
                abs[absId] = fmt ?: "bullet"
            }
            val map = mutableMapOf<String, String>()
            for (num in root.children.filter { it.name == "num" }) {
                val numId = attrInt(num, "numId") ?: continue
                val absId = attrInt(first(num, "abstractNumId"), "val") ?: continue
                map[numId.toString()] = abs[absId] ?: "bullet"
            }
            listCounters.clear()
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseDocxRun(node: Node): DRun {
        val rPr = first(node, "rPr")
        val bold = first(rPr, "b") != null
        val italic = first(rPr, "i") != null
        var size: Float? = null
        first(rPr, "sz")?.attr("val")?.toFloatOrNull()?.let { size = it / 2f }
        var color: Int? = null
        first(rPr, "color")?.attr("val")?.let { v ->
            color = try { v.toLong(16).toInt() and 0xFFFFFF } catch (_: Exception) { null }
        }
        val underline = first(rPr, "u") != null
        val strike = first(rPr, "strike") != null
        val sb = StringBuilder()
        var rid: String? = null
        for (c in node.children) {
            when (c.name) {
                "t" -> sb.append(c.text)
                "tab" -> sb.append('\t')
                "br" -> {
                    if (c.attr("type") == "page") sb.append(PAGE_BREAK_CHAR) else sb.append('\n')
                }
                "drawing", "pict" -> findBlip(c)?.let { b -> attrNs(b, NS_R, "embed")?.let { rid = it } }
            }
        }
        return DRun(sb.toString(), bold, italic, size, color, underline, strike, rid)
    }

    private fun parseDocxTable(node: Node): DTable {
        val rows = mutableListOf<List<String>>()
        for (tr in node.children.filter { it.name == "tr" }) {
            val cells = tr.children.filter { it.name == "tc" }.map { cell ->
                val sb = StringBuilder()
                collectText(cell, sb)
                sb.toString().trim()
            }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        return DTable(rows)
    }

    private fun collectText(node: Node, sb: StringBuilder) {
        for (c in node.children) {
            when (c.name) {
                "t" -> sb.append(c.text)
                "tab" -> sb.append("  ")
                "br" -> sb.append('\n')
                "p" -> {
                    collectText(c, sb)
                    sb.append('\n')
                }
                else -> collectText(c, sb)
            }
        }
    }

    private fun readRels(zip: ZipFile, relPath: String): Map<String, String> {
        val entry = zip.getEntry(relPath) ?: return emptyMap()
        val root = parseXml(zip.getInputStream(entry))
        val map = mutableMapOf<String, String>()
        first(root, "Relationships")?.children?.forEach { rel ->
            val id = rel.attr("Id") ?: return@forEach
            val target = rel.attr("Target") ?: return@forEach
            map[id] = target
        }
        return map
    }

    private fun findBlip(node: Node): Node? {
        if (node.name == "blip") return node
        for (c in node.children) {
            findBlip(c)?.let { return it }
        }
        return null
    }

    private fun findLast(node: Node, name: String): Node? =
        node.children.lastOrNull { it.name == name }

    private fun anchorOf(shape: Node): Array<Int?> {
        val xfrm = descendant(shape, "xfrm")
        val off = xfrm?.let { descendant(it, "off") }
        val ext = xfrm?.let { descendant(it, "ext") }
        return arrayOf(attrInt(off, "x"), attrInt(off, "y"), attrInt(ext, "cx"), attrInt(ext, "cy"))
    }

    private fun descendant(node: Node?, name: String): Node? {
        if (node == null) return null
        for (c in node.children) {
            if (c.name == name) return c
            descendant(c, name)?.let { return it }
        }
        return null
    }

    private fun solidColor(rPr: Node): Int? {
        val rgb = first(first(rPr, "solidFill"), "srgbClr") ?: return null
        val v = rgb.attr("val") ?: return null
        return try {
            v.toLong(16).toInt() and 0xFFFFFF
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTarget(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val result = mutableListOf<String>()
        for (p in (baseDir.split('/') + target.split('/'))) {
            when (p) {
                "", "." -> {}
                ".." -> if (result.isNotEmpty()) result.removeAt(result.size - 1)
                else -> result.add(p)
            }
        }
        return result.joinToString("/")
    }

    private fun relPathOf(slideFile: String): String {
        val slash = slideFile.lastIndexOf('/')
        val dir = if (slash > 0) slideFile.substring(0, slash) else ""
        val name = slideFile.substring(slash + 1).substringBeforeLast('.')
        return "$dir/_rels/$name.xml.rels"
    }

    private fun parseAlign(v: String?): Align = when (v) {
        "center", "centre" -> Align.CENTER
        "right" -> Align.RIGHT
        else -> Align.LEFT
    }

    // ---------------------------------------------------------------- XML tree

    private class Node(val name: String, val ns: String) {
        val attrs = mutableListOf<Pair<String, String>>()
        val children = mutableListOf<Node>()
        var text = ""
        fun attr(localName: String): String? =
            attrs.firstOrNull { it.first == localName }?.second
    }

    private fun parseXml(input: InputStream): Node {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val p = factory.newPullParser()
        p.setInput(input, "UTF-8")
        var event = p.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = p.next()
        if (event == XmlPullParser.END_DOCUMENT) throw IOException("empty xml")
        return readNode(p)
    }

    private fun readNode(p: XmlPullParser): Node {
        val node = Node(p.name, p.namespace ?: "")
        for (i in 0 until p.attributeCount) {
            node.attrs.add((p.getAttributeName(i) ?: "") to (p.getAttributeValue(i) ?: ""))
        }
        while (true) {
            val event = p.next()
            when (event) {
                XmlPullParser.START_TAG -> node.children.add(readNode(p))
                XmlPullParser.TEXT -> node.text += p.text
                XmlPullParser.END_TAG, XmlPullParser.END_DOCUMENT -> return node
                else -> {}
            }
        }
    }

    private fun first(node: Node?, name: String): Node? =
        node?.children?.firstOrNull { it.name == name }

    private fun attrInt(node: Node?, localName: String): Int? =
        node?.attr(localName)?.toFloatOrNull()?.toInt()

    private fun attrNs(node: Node, ns: String, localName: String): String? =
        node.attr(localName)

    // ---------------------------------------------------------------- layout

    private enum class Align { LEFT, CENTER, RIGHT }

    private class PdfWriter(val doc: PDDocument, val font: PDType0Font, val pageW: Float, val pageH: Float, val margin: Float) {
        var page: PDPage = newPage()
        var cs: PDPageContentStream = newStream(page)
        var y: Float = pageH - margin
        val contentW = pageW - 2 * margin

        private fun newPage(): PDPage {
            val p = PDPage(PDRectangle(pageW, pageH))
            doc.addPage(p)
            return p
        }

        private fun newStream(p: PDPage): PDPageContentStream =
            PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true)

        fun newPageNow() {
            cs.close()
            page = newPage()
            cs = newStream(page)
            y = pageH - margin
        }

        fun ensure(needed: Float) {
            if (y - needed < margin) newPageNow()
        }

        fun drawParagraph(
            text: String, size: Float, align: Align,
            bold: Boolean = false, color: Int? = null, underline: Boolean = false,
            spaceAfter: Float = 0f, indent: Float = 0f
        ) {
            if (text.isBlank()) {
                ensure(size * 1.2f + spaceAfter)
                y -= size * 1.2f + spaceAfter
                return
            }
            val availW = contentW - indent
            val lines = wrap(text, font, size, availW)
            cs.setFont(font, size)
            if (bold) {
                cs.setRenderingMode(RenderingMode.FILL_STROKE)
                cs.setLineWidth(0.35f)
            }
            if (color != null) {
                cs.setNonStrokingColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
            }
            for (line in lines) {
                ensure(size * 1.2f)
                val lw = font.getStringWidth(line) / 1000f * size
                val sx = when (align) {
                    Align.CENTER -> margin + indent + (availW - lw) / 2f
                    Align.RIGHT -> margin + indent + (availW - lw)
                    else -> margin + indent
                }
                val baseline = y - size * 0.8f
                try {
                    cs.beginText()
                    cs.newLineAtOffset(sx, baseline)
                    cs.showText(line)
                    cs.endText()
                } catch (_: Exception) {
                }
                if (underline) {
                    try {
                        cs.setLineWidth(0.5f)
                        cs.moveTo(sx, baseline - 1.5f)
                        cs.lineTo(sx + lw, baseline - 1.5f)
                        cs.stroke()
                    } catch (_: Exception) {
                    }
                }
                y -= size * 1.2f
            }
            if (bold) cs.setRenderingMode(RenderingMode.FILL)
            if (color != null) cs.setNonStrokingColor(0, 0, 0)
            ensure(spaceAfter)
            y -= spaceAfter
        }
    }

    private fun createDoc(out: File, pageW: Float, pageH: Float, margin: Float, body: (PdfWriter, PDType0Font) -> Unit) {
        val doc = PDDocument()
        try {
            val font = loadFont(doc)
            val w = PdfWriter(doc, font, pageW, pageH, margin)
            body(w, font)
            w.cs.close()
            doc.save(out)
        } finally {
            doc.close()
        }
    }

    private fun loadFont(doc: PDDocument): PDType0Font {
        return fontProvider().use { ins ->
            PDType0Font.load(doc, ins, true)
        }
    }

    // ---------------------------------------------------------------- helpers

    private sealed class Block
    private data class DParagraph(val runs: List<DRun>, val align: String?, val bullet: String?, val pageBreakBefore: Boolean) : Block()
    private data class DTable(val rows: List<List<String>>) : Block()
    private data class DRun(val text: String, val bold: Boolean, val italic: Boolean, val size: Float?, val color: Int?, val underline: Boolean, val strike: Boolean, val image: String?)

    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</(script|style)>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h[1-6])[^>]*>"), "\n")
        s = s.replace(Regex("(?i)</td>|</th>"), "\t")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'")
        return s
    }
}
