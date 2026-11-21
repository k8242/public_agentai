package io.github.k8242.threadlog.feature.backup.cloud

import io.github.k8242.threadlog.core.model.BackupWorkbook
import io.github.k8242.threadlog.feature.backup.sync.WorkbookMapper
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pure-OkHttp Google Sheets v4 REST client supporting 3-tab workbook creation, batch writing,
 * and batch reading with an injectable [baseUrl] for `MockWebServer` testing.
 */
class GoogleSheetsClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun createWorkbookSpreadsheet(
        accessToken: String,
        title: String = DEFAULT_SPREADSHEET_TITLE,
    ): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val payload = CreateSpreadsheetRequest(
                properties = SpreadsheetProperties(title = title),
                sheets = listOf(
                    SheetSpec(SheetProperties(title = WorkbookMapper.TAB_ENTRIES)),
                    SheetSpec(SheetProperties(title = WorkbookMapper.TAB_TAG_TREE)),
                    SheetSpec(SheetProperties(title = WorkbookMapper.TAB_SYNC_META)),
                ),
            )
            val url = normalizedBaseUrl().newBuilder()
                .addPathSegments("v4/spreadsheets")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val body = executeForBody(request)
            val response = json.decodeFromString<CreateSpreadsheetResponse>(body)
            response.spreadsheetId.takeIf { it.isNotBlank() }
                ?: throw IOException("Google Sheets API did not return a spreadsheetId")
        }
    }

    suspend fun writeWorkbook(
        accessToken: String,
        spreadsheetId: String,
        workbook: BackupWorkbook,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            require(spreadsheetId.isNotBlank()) { "spreadsheetId must not be blank" }

            // 1. Clear all three tabs so stale rows from previous exports are removed cleanly.
            val clearPayload = BatchClearRequest(
                ranges = listOf(
                    "${WorkbookMapper.TAB_ENTRIES}!A:L",
                    "${WorkbookMapper.TAB_TAG_TREE}!A:F",
                    "${WorkbookMapper.TAB_SYNC_META}!A:E",
                ),
            )
            val clearUrl = normalizedBaseUrl().newBuilder()
                .addPathSegments("v4/spreadsheets")
                .addPathSegment(spreadsheetId)
                .addPathSegment("values:batchClear")
                .build()

            val clearRequest = Request.Builder()
                .url(clearUrl)
                .header("Authorization", "Bearer $accessToken")
                .post(json.encodeToString(clearPayload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeForBody(clearRequest)

            // 2. Batch-update all three tabs in one atomic request.
            val updatePayload = BatchUpdateValuesRequest(
                valueInputOption = "RAW",
                data = listOf(
                    ValueRangeDto(
                        range = "${WorkbookMapper.TAB_ENTRIES}!A1",
                        values = WorkbookMapper.entriesToTableRows(workbook.entries),
                    ),
                    ValueRangeDto(
                        range = "${WorkbookMapper.TAB_TAG_TREE}!A1",
                        values = WorkbookMapper.tagsToTableRows(workbook.tags),
                    ),
                    ValueRangeDto(
                        range = "${WorkbookMapper.TAB_SYNC_META}!A1",
                        values = WorkbookMapper.metaToTableRows(workbook.meta),
                    ),
                ),
            )
            val updateUrl = normalizedBaseUrl().newBuilder()
                .addPathSegments("v4/spreadsheets")
                .addPathSegment(spreadsheetId)
                .addPathSegment("values:batchUpdate")
                .build()

            val updateRequest = Request.Builder()
                .url(updateUrl)
                .header("Authorization", "Bearer $accessToken")
                .post(json.encodeToString(updatePayload).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            executeForBody(updateRequest)
            Unit
        }
    }

    suspend fun readWorkbook(
        accessToken: String,
        spreadsheetId: String,
    ): Result<BackupWorkbook> = withContext(ioDispatcher) {
        runCatching {
            require(spreadsheetId.isNotBlank()) { "spreadsheetId must not be blank" }

            val url = normalizedBaseUrl().newBuilder()
                .addPathSegments("v4/spreadsheets")
                .addPathSegment(spreadsheetId)
                .addPathSegment("values:batchGet")
                .addQueryParameter("ranges", "${WorkbookMapper.TAB_ENTRIES}!A1:L")
                .addQueryParameter("ranges", "${WorkbookMapper.TAB_TAG_TREE}!A1:F")
                .addQueryParameter("ranges", "${WorkbookMapper.TAB_SYNC_META}!A1:E")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val body = executeForBody(request)
            val response = json.decodeFromString<BatchGetValuesResponse>(body)
            val ranges = response.valueRanges

            fun findTabValues(tabName: String, fallbackIndex: Int): List<List<String>> {
                val byName = ranges.firstOrNull { dto ->
                    val prefix = dto.range.substringBefore('!').trim().trim('\'')
                    prefix.equals(tabName, ignoreCase = true)
                }
                return (byName ?: ranges.getOrNull(fallbackIndex))?.values.orEmpty()
            }

            WorkbookMapper.tableRowsToWorkbook(
                entryRows = findTabValues(WorkbookMapper.TAB_ENTRIES, 0),
                tagRows = findTabValues(WorkbookMapper.TAB_TAG_TREE, 1),
                metaRows = findTabValues(WorkbookMapper.TAB_SYNC_META, 2),
            )
        }
    }

    private fun normalizedBaseUrl() = baseUrl.trimEnd('/').toHttpUrl()

    private fun executeForBody(request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "Google Sheets API error ${response.code}: ${responseText.take(300)}",
                )
            }
            return responseText
        }
    }

    @Serializable
    private data class CreateSpreadsheetRequest(
        val properties: SpreadsheetProperties,
        val sheets: List<SheetSpec>,
    )

    @Serializable
    private data class SpreadsheetProperties(
        val title: String,
    )

    @Serializable
    private data class SheetSpec(
        val properties: SheetProperties,
    )

    @Serializable
    private data class SheetProperties(
        val title: String,
    )

    @Serializable
    private data class CreateSpreadsheetResponse(
        val spreadsheetId: String = "",
    )

    @Serializable
    private data class BatchClearRequest(
        val ranges: List<String>,
    )

    @Serializable
    private data class BatchUpdateValuesRequest(
        val valueInputOption: String,
        val data: List<ValueRangeDto>,
    )

    @Serializable
    private data class ValueRangeDto(
        val range: String = "",
        val values: List<List<String>> = emptyList(),
    )

    @Serializable
    private data class BatchGetValuesResponse(
        val valueRanges: List<ValueRangeDto> = emptyList(),
    )

    companion object {
        const val DEFAULT_BASE_URL: String = "https://sheets.googleapis.com"
        const val DEFAULT_SPREADSHEET_TITLE: String = "ThreadLog Backup"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
