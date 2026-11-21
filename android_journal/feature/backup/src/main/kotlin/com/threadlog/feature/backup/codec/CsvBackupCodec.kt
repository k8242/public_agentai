package com.threadlog.feature.backup.codec

import com.threadlog.core.model.BackupWorkbook
import com.threadlog.feature.backup.sync.WorkbookMapper
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RFC 4180 compliant CSV codec for the `Entries` tab.
 *
 * Works completely offline with zero cloud setup, accurately preserving embedded commas,
 * double quotes, and multiline `body_raw` entries. When importing standalone CSV files,
 * synthesizes `TagTree` from all referenced tag paths and computes a fresh `SyncMetaRecord`.
 */
@Singleton
class CsvBackupCodec @Inject constructor() {

    fun write(workbook: BackupWorkbook, outputStream: OutputStream) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
        val rows = WorkbookMapper.entriesToTableRows(workbook.entries)
        for (row in rows) {
            writer.write(encodeRecord(row))
            writer.write(CRLF)
        }
        writer.flush()
    }

    fun encodeToString(workbook: BackupWorkbook): String {
        val rows = WorkbookMapper.entriesToTableRows(workbook.entries)
        return buildString {
            for (row in rows) {
                append(encodeRecord(row))
                append(CRLF)
            }
        }
    }

    fun read(inputStream: InputStream): BackupWorkbook {
        val text = inputStream.bufferedReader(Charsets.UTF_8).readText()
        return decodeFromString(text)
    }

    fun decodeFromString(csvText: String): BackupWorkbook {
        val cleaned = if (csvText.startsWith('\uFEFF')) csvText.substring(1) else csvText
        val table = parseCsvTable(cleaned)
        val entries = WorkbookMapper.tableRowsToEntries(table)
        val synthesizedTags = WorkbookMapper.synthesizeTagTreeFromEntries(entries)
        val meta = WorkbookMapper.tableRowsToMeta(
            rows = emptyList(),
            fallbackEntries = entries,
            fallbackTags = synthesizedTags,
        )
        return BackupWorkbook(
            entries = entries,
            tags = synthesizedTags,
            meta = meta,
        )
    }

    internal fun encodeRecord(fields: List<String>): String =
        fields.joinToString(",") { escapeField(it) }

    private fun escapeField(value: String): String {
        val needsQuoting = value.any { ch ->
            ch == ',' || ch == '"' || ch == '\n' || ch == '\r'
        }
        if (!needsQuoting) return value
        return buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                if (ch == '"') {
                    append("\"\"")
                } else {
                    append(ch)
                }
            }
            append('"')
        }
    }

    internal fun parseCsvTable(input: String): List<List<String>> {
        if (input.isEmpty()) return emptyList()

        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldStartedWithQuote = false
        var i = 0
        val length = input.length

        while (i < length) {
            val ch = input[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < length && input[i + 1] == '"') {
                        // Escaped double quote inside quoted field.
                        field.append('"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    field.append(ch)
                    i++
                }
            } else {
                when (ch) {
                    '"' -> {
                        if (field.isEmpty() && !fieldStartedWithQuote) {
                            inQuotes = true
                            fieldStartedWithQuote = true
                        } else {
                            field.append('"')
                        }
                        i++
                    }

                    ',' -> {
                        currentRow += field.toString()
                        field.setLength(0)
                        fieldStartedWithQuote = false
                        i++
                    }

                    '\r' -> {
                        currentRow += field.toString()
                        field.setLength(0)
                        fieldStartedWithQuote = false
                        rows += currentRow
                        currentRow = mutableListOf()
                        i += if (i + 1 < length && input[i + 1] == '\n') 2 else 1
                    }

                    '\n' -> {
                        currentRow += field.toString()
                        field.setLength(0)
                        fieldStartedWithQuote = false
                        rows += currentRow
                        currentRow = mutableListOf()
                        i++
                    }

                    else -> {
                        field.append(ch)
                        i++
                    }
                }
            }
        }

        // Flush final record if non-empty (avoid emitting a phantom blank row after trailing newline).
        if (field.isNotEmpty() || fieldStartedWithQuote || currentRow.isNotEmpty()) {
            currentRow += field.toString()
            rows += currentRow
        }

        return rows
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}
