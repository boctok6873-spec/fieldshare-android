package com.youngsu.fieldshare

import java.time.LocalDateTime

enum class OcrStatus { NOT_REQUESTED, PENDING, COMPLETED, FAILED }

data class OcrSearchUpload(
    val documentId: String,
    val title: String,
    val category: String,
    val imageUris: List<String>,
    val pdfUri: String?,
    val searchableText: String,
    val ocrStatus: OcrStatus,
    val createdAt: LocalDateTime
)

interface OcrSearchRepository {
    suspend fun uploadSearchData(document: OcrSearchUpload): Result<String>
    suspend fun searchDocumentIds(keyword: String): Result<List<String>>
    suspend fun deleteSearchData(documentId: String): Result<Unit>
}

class CloudSearchNotConfiguredException : IllegalStateException("클라우드 검색 연결이 설정되지 않았습니다.")

/**
 * Cloud Functions 또는 전용 검색엔진 HTTPS API가 준비되면 이 인터페이스에 연결한다.
 * 현재 화면 검색은 Firestore에서 내려받은 searchableText를 현재 세션에서 필터링한다.
 */
object UnavailableOcrSearchRepository : OcrSearchRepository {
    override suspend fun uploadSearchData(document: OcrSearchUpload): Result<String> =
        Result.failure(CloudSearchNotConfiguredException())

    override suspend fun searchDocumentIds(keyword: String): Result<List<String>> =
        Result.failure(CloudSearchNotConfiguredException())

    override suspend fun deleteSearchData(documentId: String): Result<Unit> =
        Result.failure(CloudSearchNotConfiguredException())
}

/** In-memory fallback for the current app session; OCR text is not part of the UI document model. */
class OcrSearchIndex {
    private val entries = mutableMapOf<String, String>()

    fun index(documentId: String, searchableText: String) {
        entries[documentId] = searchableText
    }

    fun searchableTextFor(documentId: String): String = entries[documentId].orEmpty()

    fun remove(documentId: String) {
        entries.remove(documentId)
    }
}
