package link.kmaba.apdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeConverterTest {

    private fun converter(): NativeConverters {
        val fontPath = System.getProperty("test.font.path")
            ?: throw IllegalStateException("set -Dtest.font.path to a .ttf")
        return NativeConverters({ FileInputStream(fontPath) })
    }

    private fun zip(files: Map<String, ByteArray>): File {
        val f = File.createTempFile("sample", ".bin")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { zos ->
            for ((name, data) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return f
    }

    private fun png1x1(): ByteArray {
        val ints = intArrayOf(
            137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, 144, 119, 83, 222, 0, 0, 0,
            12, 73, 68, 65, 84, 120, 156, 99, 56, 97, 100, 4, 0, 2, 242, 1, 45,
            172, 106, 39, 235, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130
        )
        return ByteArray(ints.size) { ints[it].toByte() }
    }

    @Test
    fun docx_renders_text_and_images() {
        val docxXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <w:body>
                <w:p>
                  <w:pPr><w:jc w:val="center"/></w:pPr>
                  <w:r><w:rPr><w:b/></w:rPr><w:t>My Test Document</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>Hello world from the native converter. This is a longer sentence to test wrapping across multiple lines nicely.</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:drawing><wp:inline xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
                    <a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                      <a:graphicData><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
                        <pic:blipFill><a:blip r:embed="rId10"/></pic:blipFill>
                      </pic:pic></a:graphicData>
                    </a:graphic></wp:inline></w:drawing></w:r>
                </w:p>
                <w:tbl>
                  <w:tr><w:tc><w:p><w:r><w:t>Col1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Col2</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent().toByteArray()

        val relsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/img.png"/>
            </Relationships>
        """.trimIndent().toByteArray()

        // minimal 1x1 png
        val png = png1x1()

        val file = zip(mapOf(
            "word/document.xml" to docxXml,
            "word/_rels/document.xml.rels" to relsXml,
            "word/media/img.png" to png
        ))
        val out = File.createTempFile("out", ".pdf")
        converter().docxToPdf(file, out)
        assertTrue("pdf should exist and be non-empty", out.length() > 500)

        PDDocument.load(out).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("should contain heading", text.contains("My Test Document"))
            assertTrue("should contain body", text.contains("Hello world"))
            assertTrue("should contain table cell", text.contains("Col1"))
            assertEquals("one page expected", 1, doc.numberOfPages)
        }
    }

    @Test
    fun pptx_renders_text() {
        val presXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldSz cx="9144000" cy="6858000"/>
              <p:sldIdLst>
                <p:sldId id="256" r:id="rId1"/>
              </p:sldIdLst>
            </p:presentation>
        """.trimIndent().toByteArray()

        val presRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
            </Relationships>
        """.trimIndent().toByteArray()

        val slideXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:cSld>
                <p:spTree>
                  <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                  <p:grpSpPr/>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                    <p:spPr>
                      <a:xfrm><a:off x="457200" y="457200"/><a:ext cx="8230200" cy="1371600"/></a:xfrm>
                    </p:spPr>
                    <p:txBody>
                      <a:bodyPr/>
                      <a:p>
                        <a:r><a:rPr lang="en-US" sz="2800" b="1"/><a:t>My Presentation</a:t></a:r>
                      </a:p>
                      <a:p>
                        <a:r><a:rPr lang="en-US" sz="1400"/><a:t>Second slide bullet</a:t></a:r>
                      </a:p>
                    </p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent().toByteArray()

        val slideRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>
        """.trimIndent().toByteArray()

        val file = zip(mapOf(
            "ppt/presentation.xml" to presXml,
            "ppt/_rels/presentation.xml.rels" to presRels,
            "ppt/slides/slide1.xml" to slideXml,
            "ppt/slides/_rels/slide1.xml.rels" to slideRels
        ))
        val out = File.createTempFile("out", ".pdf")
        converter().pptxToPdf(file, out)
        assertTrue("pdf should exist and be non-empty", out.length() > 500)

        PDDocument.load(out).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("should contain slide title", text.contains("My Presentation"))
            assertTrue("should contain bullet", text.contains("Second slide bullet"))
            assertEquals("one page expected", 1, doc.numberOfPages)
        }
    }

    @Test
    fun txt_renders_text() {
        val txt = File.createTempFile("sample", ".txt")
        txt.writeText("Line one here.\n\nLine two after blank line.")
        val out = File.createTempFile("out", ".pdf")
        converter().textToPdf(txt, out)
        PDDocument.load(out).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains("Line one"))
            assertTrue(text.contains("Line two"))
        }
    }

    @Test
    fun pptx_renders_image() {
        val presXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldSz cx="9144000" cy="6858000"/>
              <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
            </p:presentation>
        """.trimIndent().toByteArray()
        val presRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
            </Relationships>
        """.trimIndent().toByteArray()
        val slideXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:cSld>
                <p:spTree>
                  <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                  <p:grpSpPr/>
                  <p:pic>
                    <p:nvPicPr><p:cNvPr id="2" name="Pic"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
                    <p:blipFill>
                      <a:blip r:embed="rId2"/>
                      <a:stretch><a:fillRect/></a:stretch>
                    </p:blipFill>
                    <p:spPr>
                      <a:xfrm><a:off x="1143000" y="1143000"/><a:ext cx="6858000" cy="4572000"/></a:xfrm>
                      <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                    </p:spPr>
                  </p:pic>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent().toByteArray()
        val slideRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/img.png"/>
            </Relationships>
        """.trimIndent().toByteArray()
        val file = zip(mapOf(
            "ppt/presentation.xml" to presXml,
            "ppt/_rels/presentation.xml.rels" to presRels,
            "ppt/slides/slide1.xml" to slideXml,
            "ppt/slides/_rels/slide1.xml.rels" to slideRels,
            "ppt/media/img.png" to png1x1()
        ))
        val out = File.createTempFile("out", ".pdf")
        converter().pptxToPdf(file, out)
        assertTrue("pdf should exist and be non-empty", out.length() > 500)
        PDDocument.load(out).use { doc ->
            assertEquals("one page expected", 1, doc.numberOfPages)
        }
    }
}
