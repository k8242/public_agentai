package com.threadlog.feature.backup.codec

import com.threadlog.core.model.BackupWorkbook
import com.threadlog.feature.backup.sync.WorkbookMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Pure-JVM OOXML `.xlsx` writer & reader using `java.util.zip` and standard XML DOM parsing
 * (zero Apache POI dependency).
 *
 * Produces a valid 3-sheet workbook (`Entries`, `TagTree`, `SyncMeta`) compatible with
 * Microsoft Excel, Google Sheets, and LibreOffice Calc, and supports reading both inline-string
 * and shared-string (`xl/sharedStrings.xml`) sheets edited externally.
 */
@Singleton
class XlsxBackupCodec @Inject constructor() {

    fun write(workbook: BackupWorkbook, outputStream: OutputStream) {
        val zipOut = ZipOutputStream(outputStream)

        writeZipTextEntry(zipOut, "[Content_Types].xml", buildContentTypesXml())
        writeZipTextEntry(zipOut, "_rels/.rels", buildRootRelsXml())
        writeZipTextEntry(zipOut, "xl/workbook.xml", buildWorkbookXml())
        writeZipTextEntry(zipOut, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml())
        writeZipTextEntry(zipOut, "xl/styles.xml", buildMinimalStylesXml())

        writeZipTextEntry(
            zipOut,
            "xl/worksheets/sheet1.xml",
            buildWorksheetXml(WorkbookMapper.entriesToTableRows(workbook.entries)),
        )
        writeZipTextEntry(
            zipOut,
            "xl/worksheets/sheet2.xml",
            buildWorksheetXml(WorkbookMapper.tagsToTableRows(workbook.tags)),
        )
        writeZipTextEntry(
            zipOut,
            "xl/worksheets/sheet3.xml",
            buildWorksheetXml(WorkbookMapper.metaToTableRows(workbook.meta)),
        )

        zipOut.finish()
        zipOut.flush()
    }

    fun read(inputStream: InputStream): BackupWorkbook {
        val entriesByPath = readZipEntriesSafely(inputStream)

        val sharedStrings = entriesByPath["xl/sharedStrings.xml"]
            ?.let(::parseSharedStrings)
            .orEmpty()

        val relsById = entriesByPath["xl/_rels/workbook.xml.rels"]
            ?.let(::parseRelationships)
            .orEmpty()

        val sheetPathByName = entriesByPath["xl/workbook.xml"]
            ?.let { parseWorkbookSheetTargets(it, relsById) }
            .orEmpty()

        fun resolveSheetRows(sheetName: String, fallbackPath: String): List<List<String>> {
            val targetPath = sheetPathByName.entries
                .firstOrNull { it.key.equals(sheetName, ignoreCase = true) }
                ?.value
                ?: fallbackPath
            val bytes = entriesByPath[targetPath] ?: return emptyList()
            return parseWorksheet(bytes, sharedStrings)
        }

        val entryRows = resolveSheetRows(WorkbookMapper.TAB_ENTRIES, "xl/worksheets/sheet1.xml")
        val tagRows = resolveSheetRows(WorkbookMapper.TAB_TAG_TREE, "xl/worksheets/sheet2.xml")
        val metaRows = resolveSheetRows(WorkbookMapper.TAB_SYNC_META, "xl/worksheets/sheet3.xml")

        return WorkbookMapper.tableRowsToWorkbook(
            entryRows = entryRows,
            tagRows = tagRows,
            metaRows = metaRows,
        )
    }

    private fun writeZipTextEntry(zipOut: ZipOutputStream, name: String, content: String) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(content.toByteArray(Charsets.UTF_8))
        zipOut.closeEntry()
    }

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun buildRootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun buildWorkbookXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="${WorkbookMapper.TAB_ENTRIES}" sheetId="1" r:id="rId1"/>
    <sheet name="${WorkbookMapper.TAB_TAG_TREE}" sheetId="2" r:id="rId2"/>
    <sheet name="${WorkbookMapper.TAB_SYNC_META}" sheetId="3" r:id="rId3"/>
  </sheets>
</workbook>"""

    private fun buildWorkbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
  <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun buildMinimalStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>"""

    private fun buildWorksheetXml(rows: List<List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append('\n')
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("<sheetData>")
        for ((rowIdx, row) in rows.withIndex()) {
            val rowNum = rowIdx + 1
            append("<row r=\"").append(rowNum).append("\">")
            for ((colIdx, cellValue) in row.withIndex()) {
                val cellRef = "${columnIndexToLetters(colIdx)}$rowNum"
                append("<c r=\"").append(cellRef).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(escapeXml(cellValue))
                append("</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun readZipEntriesSafely(inputStream: InputStream): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val zipIn = ZipInputStream(inputStream)
        var totalBytesRead = 0L

        while (true) {
            val entry = zipIn.nextEntry ?: break
            val rawName = entry.name
            validateSafeZipPath(rawName)
            val normalizedName = rawName.removePrefix("/")
            if (!entry.isDirectory) {
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = zipIn.read(chunk)
                    if (read <= 0) break
                    totalBytesRead += read
                    if (totalBytesRead > MAX_XLSX_UNCOMPRESSED_BYTES) {
                        throw IllegalArgumentException("XLSX archive exceeds safe uncompressed size limit")
                    }
                    buffer.write(chunk, 0, read)
                }
                result[normalizedName] = buffer.toByteArray()
            }
            zipIn.closeEntry()
        }

        return result
    }

    private fun validateSafeZipPath(name: String) {
        if (name.isBlank() || name.contains('\u0000') || name.contains('\\')) {
            throw SecurityException("Unsafe zip entry path: $name")
        }
        val segments = name.removePrefix("/").split('/')
        if (segments.any { it == ".." }) {
            throw SecurityException("Zip-slip path traversal detected: $name")
        }
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val doc = parseXmlDocument(xmlBytes)
        val siNodes = doc.getElementsByTagName("si")
        val strings = ArrayList<String>(siNodes.length)
        for (i in 0 until siNodes.length) {
            val siElem = siNodes.item(i) as? Element ?: continue
            val tNodes = siElem.getElementsByTagName("t")
            val text = buildString {
                for (j in 0 until tNodes.length) {
                    append(tNodes.item(j).textContent.orEmpty())
                }
            }
            strings += text
        }
        return strings
    }

    private fun parseRelationships(xmlBytes: ByteArray): Map<String, String> {
        val doc = parseXmlDocument(xmlBytes)
        val relNodes = doc.getElementsByTagName("Relationship")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until relNodes.length) {
            val elem = relNodes.item(i) as? Element ?: continue
            val id = elem.getAttribute("Id")
            val target = elem.getAttribute("Target")
            if (id.isNotBlank() && target.isNotBlank()) {
                val resolved = when {
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("xl/") -> target
                    else -> "xl/$target"
                }
                map[id] = resolved
            }
        }
        return map
    }

    private fun parseWorkbookSheetTargets(
        xmlBytes: ByteArray,
        relsById: Map<String, String>,
    ): Map<String, String> {
        val doc = parseXmlDocument(xmlBytes)
        val sheetNodes = doc.getElementsByTagName("sheet")
        val targets = LinkedHashMap<String, String>()
        for (i in 0 until sheetNodes.length) {
            val elem = sheetNodes.item(i) as? Element ?: continue
            val name = elem.getAttribute("name")
            val rId = elem.getAttributeNS(REL_NS, "id")
                .ifBlank { elem.getAttribute("r:id") }
            val target = relsById[rId]
            if (name.isNotBlank() && target != null) {
                targets[name] = target
            }
        }
        return targets
    }

    private fun parseWorksheet(
        xmlBytes: ByteArray,
        sharedStrings: List<String>,
    ): List<List<String>> {
        val doc = parseXmlDocument(xmlBytes)
        val rowNodes = doc.getElementsByTagName("row")
        val table = ArrayList<List<String>>(rowNodes.length)

        for (i in 0 until rowNodes.length) {
            val rowElem = rowNodes.item(i) as? Element ?: continue
            val cellsByCol = LinkedHashMap<Int, String>()
            var maxCol = -1
            var sequentialCol = 0

            val children = rowElem.childNodes
            for (j in 0 until children.length) {
                val node = children.item(j)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val cellElem = node as Element
                if (cellElem.localName != "c" && cellElem.tagName != "c") continue

                val cellRef = cellElem.getAttribute("r")
                val colIndex = parseColumnIndexFromRef(cellRef) ?: sequentialCol
                sequentialCol = colIndex + 1

                val value = extractCellValue(cellElem, sharedStrings)
                cellsByCol[colIndex] = value
                if (colIndex > maxCol) maxCol = colIndex
            }

            if (maxCol >= 0) {
                val rowList = MutableList(maxCol + 1) { "" }
                for ((colIdx, value) in cellsByCol) {
                    rowList[colIdx] = value
                }
                table += rowList
            }
        }

        return table
    }

    private fun extractCellValue(cellElem: Element, sharedStrings: List<String>): String {
        val type = cellElem.getAttribute("t")
        return when (type) {
            "inlineStr" -> {
                val tNodes = cellElem.getElementsByTagName("t")
                buildString {
                    for (k in 0 until tNodes.length) {
                        append(tNodes.item(k).textContent.orEmpty())
                    }
                }
            }

            "s" -> {
                val rawIndex = firstChildText(cellElem, "v").trim().toIntOrNull()
                if (rawIndex != null && rawIndex in sharedStrings.indices) {
                    sharedStrings[rawIndex]
                } else {
                    ""
                }
            }

            "b" -> {
                val raw = firstChildText(cellElem, "v").trim()
                if (raw == "1" || raw.equals("true", ignoreCase = true)) "true" else "false"
            }

            else -> firstChildText(cellElem, "v")
        }
    }

    private fun firstChildText(parent: Element, localTagName: String): String {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                if (elem.localName == localTagName || elem.tagName == localTagName) {
                    return elem.textContent.orEmpty()
                }
            }
        }
        return ""
    }

    private fun parseXmlDocument(xmlBytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
    }

    internal fun columnIndexToLetters(colIndex: Int): String {
        var n = colIndex + 1
        val sb = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            sb.append(('A'.code + remainder).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    internal fun parseColumnIndexFromRef(cellRef: String): Int? {
        if (cellRef.isBlank()) return null
        var index = 0
        var hasLetters = false
        for (ch in cellRef.uppercase(Locale.ROOT)) {
            if (ch in 'A'..'Z') {
                hasLetters = true
                index = index * 26 + (ch.code - 'A'.code + 1)
            } else {
                break
            }
        }
        return if (hasLetters) index - 1 else null
    }

    private fun escapeXml(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\r' -> append("&#13;")
                '\n' -> append("&#10;")
                '\t' -> append('\t')
                else -> {
                    // Strip XML 1.0 illegal control characters (< 0x20).
                    if (ch.code >= 0x20) {
                        append(ch)
                    }
                }
            }
        }
    }

    private companion object {
        const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val MAX_XLSX_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L // 50 MiB safety guard
    }
}
