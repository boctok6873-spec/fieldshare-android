package com.youngsu.fieldshare

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.Color
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StorageException
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Firestore wire format. File fields contain Storage paths, never device-local URIs. */
data class FirebaseDocumentDto(
    val id: String = "",
    val title: String = "",
    val category: String = "기타",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val source: String = DocumentSource.TEXT.name,
    val content: String = "",
    val description: String = "",
    val imagePaths: List<String> = emptyList(),
    val pdfPath: String? = null,
    val ocrStatus: String = OcrStatus.NOT_REQUESTED.name,
    val searchableText: String = "",
    val createdBy: String = "",
    val createdByName: String = "",
    val pendingDeletion: Boolean = false,
    val deletedAt: Timestamp? = null
)

data class FirebaseDocumentUpload(
    val document: FieldDocument,
    val searchableText: String,
    val imageUris: List<String>,
    val pdfUri: String?
)

enum class DocumentActivityAction { CREATED, UPDATED, DELETED }

data class DocumentActivity(
    val id: String,
    val action: DocumentActivityAction,
    val documentId: String,
    val documentTitle: String,
    val actorUid: String,
    val actorLabel: String,
    val createdAt: Timestamp?
)

sealed interface DocumentStream {
    data object Loading : DocumentStream
    data class Data(
        val documents: List<FieldDocument>,
        val isFromCache: Boolean,
        val pendingDeletionDocuments: List<FieldDocument> = emptyList()
    ) : DocumentStream
    data class Error(val message: String) : DocumentStream
}

/**
 * Public document repository. The current UID is recorded as createdBy for newly created data.
 */
class FirebaseDocumentRepository(
    private val context: Context,
    private val currentUserId: String,
    private val currentUserDisplayName: String,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    private val functions = FirebaseFunctions.getInstance()
    private val documents = firestore.collection("documents")
    private val activities = firestore.collection("activities")
    // A repository lives for the current signed-in Compose tree. Remember both successful and
    // failed resolutions so cards do not repeatedly ask Storage for the same thumbnail path.
    private val thumbnailUrlCache = ConcurrentHashMap<String, CompletableDeferred<ThumbnailUrlResolution>>()

    /**
     * Runs the authenticated Algolia Callable search, then reads only the matched Firestore
     * documents. Firestore getAll does not guarantee ordering, so the Algolia ID order is restored.
     */
    suspend fun searchDocuments(query: String): Result<List<FieldDocument>> = runCatching {
        require(query.trim().length >= 2) { "검색어는 2글자 이상 입력해 주세요." }
        @Suppress("UNCHECKED_CAST")
        val result = functions.getHttpsCallable("searchDocuments")
            .call(mapOf("query" to query.trim())).await().data as? Map<String, Any?>
        val ids = (result?.get("documents") as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
            ?.distinct()
            .orEmpty()
        if (ids.isEmpty()) return@runCatching emptyList()

        // Android Firestore does not expose a client getAll API. These are only the
        // Algolia-matched IDs (at most 50), never a collection-wide fallback read.
        val snapshots = ids.map { documentId -> documents.document(documentId).get().await() }
        val documentsById = snapshots.mapNotNull { snapshot ->
            snapshot.toDtoOrNull()
                ?.takeUnless { it.pendingDeletion }
                ?.let { dto -> dto.toListFieldDocument() }
                ?.let { document -> document.id to document }
        }.toMap()
        ids.mapNotNull(documentsById::get)
    }

    /** Callable remains server-authorized; non-admin callers receive permission-denied. */
    suspend fun reindexAllDocuments(): Result<Int> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val result = functions.getHttpsCallable("reindexAllDocuments").call().await().data as? Map<String, Any?>
        (result?.get("count") as? Number)?.toInt() ?: 0
    }

    /** Returns the server-authoritative count of documents that are still active. */
    suspend fun fetchActiveDocumentCount(): Result<Long> = runCatching {
        documents
            .whereEqualTo("pendingDeletion", false)
            .count()
            .get(AggregateSource.SERVER)
            .await()
            .count
    }

    /** Converts Storage paths to download URLs only after a user opens a document detail. */
    suspend fun resolveImageUrls(document: FieldDocument): FieldDocument {
        val resolved = document.imageUris.map { imageUri ->
            if (imageUri.startsWith("http://") || imageUri.startsWith("https://")) imageUri
            else runCatching { storage.reference.child(imageUri).downloadUrl.await().toString() }.getOrElse { imageUri }
        }
        return document.copy(imageUri = resolved.firstOrNull(), imageUris = resolved)
    }

    /** Resolves only a visible card's first image; the list snapshot itself keeps Storage paths. */
    suspend fun resolveThumbnailUrl(imagePath: String): String? {
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) return imagePath
        val createdResolution = CompletableDeferred<ThumbnailUrlResolution>()
        val resolution = thumbnailUrlCache.putIfAbsent(imagePath, createdResolution) ?: createdResolution
        if (resolution === createdResolution) {
            createdResolution.complete(
                runCatching {
                    ThumbnailUrlResolution.Success(storage.reference.child(imagePath).downloadUrl.await().toString())
                }.getOrElse { ThumbnailUrlResolution.Failure }
            )
        }
        return (resolution.await() as? ThumbnailUrlResolution.Success)?.url
    }

    fun observeDocuments(
        category: String? = null,
        displayMode: HomeDocumentDisplayMode = HomeDocumentDisplayMode.ALL
    ): Flow<DocumentStream> = callbackFlow {
        if (displayMode == HomeDocumentDisplayMode.HIDDEN) {
            trySend(DocumentStream.Data(emptyList(), false))
            awaitClose { }
            return@callbackFlow
        }
        var query: Query = documents
        // ALL mode keeps one collection-wide listener; category tabs are filtered in the UI.
        if (displayMode != HomeDocumentDisplayMode.ALL && !category.isNullOrBlank() && category != "전체") {
            query = query.whereEqualTo("category", category)
        }
        if (displayMode == HomeDocumentDisplayMode.RECENT_ONLY) {
            query = query.limit(if (category.isNullOrBlank() || category == "전체") 10 else 5)
        }
        val registration = query
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(DocumentStream.Error(error.toUserMessage()))
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                // With no cached documents (for example, a first installation), retain the
                // existing loading state until Firestore supplies a server snapshot.
                if (snapshot.metadata.isFromCache && snapshot.documents.isEmpty()) {
                    return@addSnapshotListener
                }
                // Do not await Storage here: a Firestore cache snapshot can now reach the home
                // screen immediately, and the later server snapshot naturally replaces it.
                val mapped = snapshot.documents.mapNotNull { document ->
                    document.toDtoOrNull()?.let { dto -> dto.toListFieldDocument() to dto.pendingDeletion }
                }
                trySend(
                    DocumentStream.Data(
                        documents = mapped.filterNot { it.second }.map { it.first },
                        isFromCache = snapshot.metadata.isFromCache,
                        pendingDeletionDocuments = mapped
                            .filter { (_, pendingDeletion) -> pendingDeletion }
                            .map { it.first }
                    )
                )
            }
        awaitClose { registration.remove() }
    }

    fun observeActivities(): Flow<List<DocumentActivity>> = callbackFlow {
        val currentTimeMillis = System.currentTimeMillis()
        val recentActivityCutoffMillis = currentTimeMillis - RecentActivityWindowMillis
        val registration = activities
            // Missing/unresolved server timestamps do not match this range and are safely excluded.
            .whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(recentActivityCutoffMillis)))
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                trySend(
                    recentActivitiesForDisplay(
                        activities = snapshot?.documents.orEmpty()
                            .mapNotNull { it.toDocumentActivityOrNull() },
                        // Do not reuse the listener start time here. New activities arrive after
                        // that time and must be visible in the active sharing-status screen.
                        nowMillis = System.currentTimeMillis()
                    )
                )
            }
        awaitClose { registration.remove() }
    }

    suspend fun create(upload: FirebaseDocumentUpload): Result<FieldDocument> = runCatching {
        val documentReference = documents.document()
        val documentId = documentReference.id
        val uploadedReferences = mutableListOf<StorageReference>()
        var saveStage = "이미지 업로드"
        try {
            val imagePaths = upload.imageUris.mapIndexed { index, uriString ->
                uploadAttachment(
                    uriString = uriString,
                    folder = "images",
                    fallbackName = "image_${index + 1}",
                    documentId = documentId,
                    uploadedReferences = uploadedReferences
                )
            }
            val pdfPath = upload.pdfUri?.let { uriString ->
                saveStage = "PDF 업로드"
                uploadAttachment(
                    uriString = uriString,
                    folder = "pdf",
                    fallbackName = "document",
                    documentId = documentId,
                    uploadedReferences = uploadedReferences
                )
            }
            saveStage = "Firestore 저장"
            firestore.batch().apply {
                set(
                    documentReference,
                    upload.document.toFirestoreMap(
                        documentId = documentId,
                        imagePaths = imagePaths,
                        pdfPath = pdfPath,
                        searchableText = upload.searchableText,
                        createdBy = currentUserId,
                        createdByName = currentUserDisplayName
                    )
                )
                set(
                    activities.document(),
                    activityMap(DocumentActivityAction.CREATED, documentId, upload.document.title)
                )
            }.commit().await()
            val createdDocument = FirebaseDocumentDto(
                id = documentId,
                title = upload.document.title,
                category = upload.document.category,
                source = upload.document.source.name,
                content = upload.document.content,
                description = upload.document.description,
                imagePaths = imagePaths,
                pdfPath = pdfPath,
                ocrStatus = upload.document.ocrStatus.name,
                searchableText = upload.searchableText,
                createdBy = currentUserId,
                createdByName = currentUserDisplayName
            ).toListFieldDocument()
            // create() immediately opens the detail screen, unlike observeDocuments(), so
            // resolve the newly uploaded image paths only for this returned document.
            resolveImageUrls(createdDocument)
        } catch (error: Throwable) {
            // Firestore write failures can leave uploaded objects. Best-effort cleanup is safe here.
            uploadedReferences.forEach { reference -> runCatching { reference.delete().await() } }
            throw FirebaseDocumentException("$saveStage 단계에서 ${error.toUserMessage()}", error)
        }
    }

    suspend fun updateDocumentText(
        document: FieldDocument,
        title: String,
        category: String,
        content: String
    ): Result<FieldDocument> = runCatching {
        val normalizedTitle = title.ifBlank { "제목 없는 자료" }
        val normalizedCategory = category.ifBlank { "기타" }
        val normalizedContent = content.ifBlank { "등록된 내용이 없습니다." }
        firestore.batch().apply {
            update(
                documents.document(document.id),
                mapOf(
                    "title" to normalizedTitle,
                    "category" to normalizedCategory,
                    "content" to normalizedContent,
                    "description" to normalizedContent.take(80),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            set(
                activities.document(),
                activityMap(DocumentActivityAction.UPDATED, document.id, normalizedTitle)
            )
        }.commit().await()
        document.copy(
            title = normalizedTitle,
            category = normalizedCategory,
            content = normalizedContent,
            description = normalizedContent.take(80),
            detail = normalizedTitle.take(16)
        )
    }

    suspend fun delete(
        document: FieldDocument,
        onMarkedPendingDeletion: (() -> Unit)? = null
    ): Result<Unit> = runCatching {
        val documentReference = documents.document(document.id)
        val snapshot = documentReference.get().await()
        if (!snapshot.exists()) return@runCatching
        val dto = snapshot.toDtoOrNull()
        documentReference.update(
            mapOf(
                "pendingDeletion" to true,
                "deletedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        onMarkedPendingDeletion?.invoke()
        try {
            (dto?.imagePaths.orEmpty() + listOfNotNull(dto?.pdfPath)).forEach { path ->
                storage.reference.child(path).deleteTreatingNotFoundAsSuccess()
            }
            firestore.batch().apply {
                delete(documentReference)
                set(activities.document(), activityMap(DocumentActivityAction.DELETED, document.id, document.title))
            }.commit().await()
        } catch (error: Throwable) {
            runCatching {
                documentReference.update(
                    mapOf(
                        "pendingDeletion" to true,
                        "deletionLastAttemptAt" to FieldValue.serverTimestamp(),
                        "deletionLastError" to "storage_or_firestore_delete_failed",
                        "deletionFailureCount" to 0,
                        "deletionNextRetryAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            throw FirebaseDocumentException("자료 삭제에 실패했습니다. 다시 시도해 주세요.", error)
        }
    }

    private suspend fun uploadAttachment(
        uriString: String,
        folder: String,
        fallbackName: String,
        documentId: String,
        uploadedReferences: MutableList<StorageReference>
    ): String {
        val uri = Uri.parse(uriString)
        val imageUpload = if (folder == "images") validateOptimizedImageUpload(uri) else null
        val fileName = safeFileName(
            uri.lastPathSegment ?: fallbackName,
            fallbackName + imageUpload?.fileExtension.orEmpty()
        )
        val path = "documents/$documentId/$folder/${UUID.randomUUID()}_$fileName"
        val reference = storage.reference.child(path)
        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType(imageUpload?.mimeType ?: context.contentResolver.getType(uri))
            .setCustomMetadata("ownerUid", currentUserId)
            .build()
        reference.putFile(uri, metadata).await()
        uploadedReferences += reference
        return path
    }

    private fun validateOptimizedImageUpload(uri: Uri): OptimizedImageUpload {
        if (!isOptimizedImageUri(uri, context.packageName)) {
            throw FirebaseDocumentException("최적화되지 않은 이미지는 업로드할 수 없습니다.")
        }
        val byteCount = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize }
        } catch (error: Throwable) {
            throw FirebaseDocumentException("최적화 이미지 파일을 확인할 수 없습니다.", error)
        }
        if (!isValidOptimizedImageSize(byteCount)) {
            throw FirebaseDocumentException("최적화 이미지 파일 크기가 올바르지 않거나 20MB를 초과합니다.")
        }
        val mimeType = optimizedImageMimeType(
            path = uri.path,
            resolverMimeType = context.contentResolver.getType(uri)
        ) ?: throw FirebaseDocumentException("지원하지 않는 최적화 이미지 형식입니다.")
        return OptimizedImageUpload(
            mimeType = mimeType,
            fileExtension = if (mimeType == "image/webp") ".webp" else ".jpg"
        )
    }

    private fun activityMap(
        action: DocumentActivityAction,
        documentId: String,
        documentTitle: String
    ): Map<String, Any> = mapOf(
        "action" to action.name,
        "documentId" to documentId,
        "documentTitle" to documentTitle,
        "actorUid" to currentUserId,
        "actorLabel" to currentUserDisplayName,
        "createdAt" to FieldValue.serverTimestamp()
    )
}

private data class OptimizedImageUpload(
    val mimeType: String,
    val fileExtension: String
)

internal const val MaxOptimizedImageUploadBytes = 20L * 1024L * 1024L

internal fun isValidOptimizedImageSize(byteCount: Long?): Boolean =
    byteCount != null && byteCount in 1L..MaxOptimizedImageUploadBytes

internal fun isOptimizedImageUri(uri: Uri, packageName: String): Boolean =
    isOptimizedImageLocation(uri.authority, uri.path, packageName)

internal fun isOptimizedImageLocation(authority: String?, path: String?, packageName: String): Boolean =
    authority == "$packageName.fileprovider" && path.orEmpty().contains("fieldshare_optimized_")

internal fun optimizedImageMimeType(path: String?, resolverMimeType: String?): String? = when {
    resolverMimeType == "image/jpeg" || resolverMimeType == "image/webp" -> resolverMimeType
    path.orEmpty().substringAfterLast('.', "").lowercase() == "jpg" ||
        path.orEmpty().substringAfterLast('.', "").lowercase() == "jpeg" -> "image/jpeg"
    path.orEmpty().substringAfterLast('.', "").lowercase() == "webp" -> "image/webp"
    else -> null
}

private fun FieldDocument.toFirestoreMap(
    documentId: String,
    imagePaths: List<String>,
    pdfPath: String?,
    searchableText: String,
    createdBy: String,
    createdByName: String
): Map<String, Any?> = mapOf(
    "id" to documentId,
    "title" to title,
    "category" to category,
    "createdAt" to FieldValue.serverTimestamp(),
    "updatedAt" to FieldValue.serverTimestamp(),
    "source" to source.name,
    "content" to content,
    "description" to description,
    "imagePaths" to imagePaths,
    "pdfPath" to pdfPath,
    "ocrStatus" to ocrStatus.name,
    "searchableText" to searchableText,
    "createdBy" to createdBy,
    "createdByName" to createdByName,
    "pendingDeletion" to false,
    "deletedAt" to null
)

private fun DocumentSnapshot.toDtoOrNull(): FirebaseDocumentDto? = runCatching {
    FirebaseDocumentDto(
        id = getString("id").orEmpty().ifBlank { id },
        title = getString("title").orEmpty(),
        category = getString("category") ?: "기타",
        // A local write with serverTimestamp() has no confirmed value yet. ESTIMATE keeps
        // the registering device's new document dated and sorted as newest until confirmation.
        createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt"),
        source = getString("source") ?: DocumentSource.TEXT.name,
        content = getString("content").orEmpty(),
        description = getString("description").orEmpty(),
        imagePaths = (get("imagePaths") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        pdfPath = getString("pdfPath"),
        ocrStatus = getString("ocrStatus") ?: OcrStatus.NOT_REQUESTED.name,
        searchableText = getString("searchableText").orEmpty(),
        createdBy = getString("createdBy").orEmpty(),
        createdByName = getString("createdByName").orEmpty(),
        pendingDeletion = getBoolean("pendingDeletion") ?: false,
        deletedAt = getTimestamp("deletedAt")
    )
}.getOrNull()

private fun DocumentSnapshot.toDocumentActivityOrNull(): DocumentActivity? = runCatching {
    DocumentActivity(
        id = id,
        action = DocumentActivityAction.valueOf(getString("action").orEmpty()),
        documentId = getString("documentId").orEmpty(),
        documentTitle = getString("documentTitle").orEmpty(),
        actorUid = getString("actorUid").orEmpty(),
        actorLabel = getString("actorLabel").orEmpty().ifBlank { "익명 사용자" },
        createdAt = getTimestamp("createdAt")
    )
}.getOrNull()

/** Pure Firestore-list mapping: image fields deliberately remain Storage paths. */
internal fun FirebaseDocumentDto.toListFieldDocument(): FieldDocument {
    val sourceValue = runCatching { DocumentSource.valueOf(source) }.getOrDefault(DocumentSource.TEXT)
    val statusValue = runCatching { OcrStatus.valueOf(ocrStatus) }.getOrDefault(OcrStatus.NOT_REQUESTED)
    val presentation = categoryPresentation(category, sourceValue)
    return FieldDocument(
        id = id,
        title = title,
        category = category,
        date = formatRegistrationDate(createdAt?.toDate()?.time),
        createdAtMillis = createdAt?.toDate()?.time,
        source = sourceValue,
        content = content,
        imageUri = imagePaths.firstOrNull(),
        imageUris = imagePaths,
        pdfUri = pdfPath,
        ocrStatus = statusValue,
        detail = title.take(16),
        description = description.ifBlank { content.take(80) },
        thumbnailColor = presentation.first,
        icon = presentation.second,
        searchableText = searchableText,
        createdBy = createdBy,
        createdByName = displayCreatorName(createdByName)
    )
}

private sealed interface ThumbnailUrlResolution {
    data class Success(val url: String) : ThumbnailUrlResolution
    data object Failure : ThumbnailUrlResolution
}

internal fun displayCreatorName(createdByName: String?): String =
    createdByName.orEmpty().trim().ifBlank { "익명 사용자" }

private fun categoryPresentation(category: String, source: DocumentSource) = when (category) {
    "에어컨" -> Color(0xFFE5F2FF) to Icons.Default.Thermostat
    "TV" -> Color(0xFFE9F6FA) to Icons.Default.Tv
    "냉장고" -> Color(0xFFFFF2E5) to Icons.Default.WbSunny
    else -> if (source == DocumentSource.IMAGE) Color(0xFFE5F2FF) to Icons.Default.Image
    else Color(0xFFEAE4FA) to Icons.AutoMirrored.Filled.Article
}

internal const val RecentActivityWindowMillis = 30L * 24L * 60L * 60L * 1000L

internal fun isCreatedWithinRecentActivityWindow(createdAtMillis: Long?, nowMillis: Long): Boolean =
    createdAtMillis != null && createdAtMillis in (nowMillis - RecentActivityWindowMillis)..nowMillis

internal fun recentActivitiesForDisplay(
    activities: List<DocumentActivity>,
    nowMillis: Long
): List<DocumentActivity> = activities.filter { activity ->
    isCreatedWithinRecentActivityWindow(
        createdAtMillis = activity.createdAt?.toDate()?.time,
        nowMillis = nowMillis
    )
}

internal fun formatRegistrationDate(createdAtMillis: Long?): String = createdAtMillis?.let { millis ->
    DateTimeFormatter.ofPattern("yyyy.MM.dd")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Seoul")))
} ?: "등록일 확인 중"

private fun safeFileName(name: String, fallback: String): String =
    name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { fallback }

private fun Throwable.toUserMessage(): String = when ((this as? FirebaseFirestoreException)?.code) {
    FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Firebase 접근 권한이 없습니다. 로그인과 보안 규칙을 확인해 주세요."
    FirebaseFirestoreException.Code.UNAVAILABLE -> "네트워크에 연결할 수 없습니다. 연결 후 다시 시도해 주세요."
    else -> when (this) {
        is com.google.firebase.storage.StorageException -> "파일 업로드 또는 다운로드에 실패했습니다. 네트워크와 Storage 규칙을 확인해 주세요."
        else -> "Firebase 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."
    }
}

class FirebaseDocumentException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private suspend fun StorageReference.deleteTreatingNotFoundAsSuccess() {
    try {
        delete().await()
    } catch (error: StorageException) {
        if (error.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw error
    }
}
