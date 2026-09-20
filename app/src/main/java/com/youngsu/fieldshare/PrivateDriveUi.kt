package com.youngsu.fieldshare

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.identity.Identity
import com.youngsu.fieldshare.ui.theme.BorderGray
import com.youngsu.fieldshare.ui.theme.Ink
import com.youngsu.fieldshare.ui.theme.SamsungBlue
import com.youngsu.fieldshare.ui.theme.SamsungBlueDark
import com.youngsu.fieldshare.ui.theme.SamsungBlueLight
import com.youngsu.fieldshare.ui.theme.SoftGray
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DriveSettings(repository: DriveConnectionRepository) {
    val state by repository.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val client = remember { Identity.getAuthorizationClient(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) repository.authorizationCancelled()
        else scope.launch {
            try { repository.finishAuthorization(client.getAuthorizationResultFromIntent(result.data)) }
            catch (_: Exception) { repository.authorizationFailed() }
        }
    }
    val connect: () -> Unit = {
        scope.launch {
            try {
                val result = repository.beginAuthorization(true)
                if (result.hasResolution()) launcher.launch(IntentSenderRequest.Builder(checkNotNull(result.pendingIntent).intentSender).build())
                else { repository.finishAuthorization(result) }
            } catch (_: Exception) { repository.authorizationFailed() }
        }
    }
    val reauthorize: () -> Unit = {
        scope.launch {
            try {
                val result = repository.beginAuthorization(false)
                if (result.hasResolution()) launcher.launch(IntentSenderRequest.Builder(checkNotNull(result.pendingIntent).intentSender).build())
                else { repository.finishAuthorization(result) }
            } catch (_: Exception) { repository.authorizationFailed() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("개인 자료 저장소", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text("개인 자료는 선택한 Google 계정의 Drive에 저장됩니다.")
        if (state.email == null) Button(onClick = connect, enabled = !state.busy) { Text("Google Drive 연결") }
        else {
            Text(checkNotNull(state.email))
            Text(when { state.syncing -> "Drive 연결됨 · 자료 동기화 중…"; state.verified -> "Drive 연결됨"; else -> "Drive 권한 확인 필요 · 캐시 검색 가능" })
            Text(
                "(마지막 동기화: ${if (state.lastSync == 0L) "아직 없음" else DateFormat.getDateTimeInstance().format(Date(state.lastSync))})",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Button(onClick = { scope.launch { repository.sync() } }, enabled = !state.busy && !state.syncing) { Text("지금 동기화") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = reauthorize, enabled = !state.busy) { Text("현재 계정 재인증") }
                OutlinedButton(onClick = connect, enabled = !state.busy && !state.pendingSave) { Text("Google 계정 변경") }
                OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !state.busy && !state.pendingSave) { Text("Drive 연결 해제") }
            }
            Text("연결 해제는 기기의 개인 자료 캐시만 정리하며 Drive 원본은 삭제하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
        DriveProgress(repository, state)
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text("Drive 연결 해제") },
        text = { Text("기기에 저장된 계정 정보와 개인 자료 캐시를 지웁니다. Drive 원본은 유지됩니다." +
            if (state.pendingCleanup > 0) " 아직 완료하지 못한 삭제 정리 ${state.pendingCleanup}건은 지금 동기화로 재시도할 수 있습니다. 지금 해제하면 이 기기의 재시도 기록도 지워집니다." else "") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; scope.launch { repository.disconnect() } }) { Text("연결 해제") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("취소") } })
}

@Composable
private fun DriveProgress(repository: DriveConnectionRepository, state: DriveUiState, showNormalProgress: Boolean = true) {
    val scope = rememberCoroutineScope()
    if (showNormalProgress && state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.message?.takeIf { showNormalProgress || state.messageIsError }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (state.messageIsError) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
    if (state.syncing) Text("Drive 자료를 불러오는 중입니다. 확인되는 자료부터 표시합니다.", style = MaterialTheme.typography.bodySmall)
    state.syncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (deleteRetryVisible(state)) {
        Text(if (state.deleteRequiresReauthorization) "자료 삭제 정리에 Drive 재인증이 필요합니다." else "자료 삭제 동기화 실패 · 재시도",
            color = MaterialTheme.colorScheme.error)
        Text(checkNotNull(state.deleteSyncError), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { scope.launch { repository.retryDelete() } }, enabled = !state.busy) { Text("삭제 재시도") }
    } else if (state.pendingSave) {
        when (state.pendingSyncStatus) {
            PrivateSyncStatus.WAITING, PrivateSyncStatus.SYNCING -> Unit
            PrivateSyncStatus.FAILED -> {
                Text("Google Drive 동기화 실패 · 재시도", color = MaterialTheme.colorScheme.error)
                state.pendingSyncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            PrivateSyncStatus.ACTION_REQUIRED -> {
                Text("Google Drive 동기화에 사용자 조치가 필요합니다.", color = MaterialTheme.colorScheme.error)
                state.pendingSyncError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            PrivateSyncStatus.CANCELLED -> {
                if (state.pendingSyncError != null) {
                    Text("취소 정리에 사용자 조치가 필요합니다.", color = MaterialTheme.colorScheme.error)
                    Text(state.pendingSyncError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> Unit
        }
        Row {
            TextButton(onClick = { scope.launch { repository.retrySave() } }, enabled = !state.busy) { Text("동기화 재시도") }
            TextButton(onClick = { scope.launch { repository.cancelSave() } }, enabled = !state.busy) { Text("미완료 저장 취소") }
        }
    }
    if (showNormalProgress && state.pendingCleanup > 0) {
        Text("완료되지 않은 Drive 파일 정리 ${state.pendingCleanup}건")
        TextButton(onClick = { scope.launch { repository.sync() } }, enabled = !state.busy && !state.syncing) { Text("삭제·정리 재시도") }
    }
}

@Composable
internal fun PrivateLibrary(repository: DriveConnectionRepository, query: String, mode: HomeDocumentDisplayMode,
    scope: CoroutineScope, onProfile: () -> Unit, onDocumentClick: (PrivateDocument) -> Unit) {
    val state by repository.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.email == null) {
            Text("내 자료를 사용하려면 내 정보에서 Google Drive를 연결해 주세요.")
            Button(onClick = onProfile) { Text("내 정보로 이동") }
            return@Column
        }
        Text(
            "(개인자료는 연결된 구글 드라이브에 저장됩니다)",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.copy(alpha = 0.56f)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("최신 등록순", style = MaterialTheme.typography.labelLarge,
                color = Ink.copy(alpha = 0.72f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { scope.launch { repository.sync() } }, enabled = privateSyncButtonEnabled(state)) {
                Text(privateSyncButtonLabel(state))
            }
        }
        DriveProgress(repository, state, showNormalProgress = false)
        val matching = searchPrivateDocuments(state.documents, query)
        val visible = if (query.isNotBlank()) matching else when (mode) {
            HomeDocumentDisplayMode.ALL -> matching
            HomeDocumentDisplayMode.RECENT_ONLY -> matching.take(10)
            HomeDocumentDisplayMode.HIDDEN -> emptyList()
        }
        if (state.syncing) {
            Text("자료를 불러오는 중이며, 확인된 자료부터 표시됩니다.", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            if (visible.isEmpty()) item {
                Text(when {
                    state.syncing || state.busy -> "개인 자료를 불러오는 중…"
                    query.isNotBlank() -> "동기화된 자료에서 검색 결과가 없습니다."
                    mode == HomeDocumentDisplayMode.HIDDEN -> "홈 화면 자료 표시가 꺼져 있습니다. 검색은 사용할 수 있습니다."
                    else -> "등록된 개인 자료가 없습니다."
                })
            }
            items(visible, key = { document ->
                document.fileId.ifBlank { "${document.id}:${document.revision}" }
            }) { document ->
                PrivateDocumentCard(repository = repository, document = document, onClick = { onDocumentClick(document) })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PrivateDocumentDetailScreen(
    repository: DriveConnectionRepository,
    selected: PrivateDocument,
    onBack: () -> Unit,
    onImageClick: (String) -> Unit,
    scope: CoroutineScope
) {
    val state by repository.state.collectAsState()
    val context = LocalContext.current
    val document = resolvePrivateDetailDocument(selected, state.documents)
    var editing by remember(selected.revision) { mutableStateOf(false) }
    var title by remember(selected.revision) { mutableStateOf(selected.title) }
    var content by remember(selected.revision) { mutableStateOf(selected.content) }
    var confirmDelete by remember(selected.revision) { mutableStateOf(false) }
    BackHandler(enabled = !state.busy) { onBack() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("자료 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("이 자료를 삭제할까요? 메타데이터와 첨부파일은 Drive 휴지통으로 이동합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    startPrivateDelete(onBack) { scope.launch { repository.delete(document) } }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }

    Scaffold(
        containerColor = SoftGray,
        topBar = {
            Column(modifier = Modifier.background(Brush.verticalGradient(listOf(SamsungBlueDark, SamsungBlue)))) {
                CenterAlignedTopAppBar(
                    title = { Text("자료 상세", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White
                    )
                )
            }
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    if (document.remoteState == PrivateRemoteState.AVAILABLE) {
                        IconButton(onClick = { editing = !editing }, enabled = !state.busy && !state.pendingSave) {
                            Icon(Icons.Default.Edit, contentDescription = "자료 수정", tint = SamsungBlue)
                        }
                        IconButton(onClick = { confirmDelete = true }, enabled = !state.busy && !state.pendingSave) {
                            Icon(Icons.Default.Delete, contentDescription = "자료 삭제", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 화면")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            DriveProgress(repository, state)
            if (editing) {
                OutlinedTextField(title, { title = it }, label = { Text("제목") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(content, { content = it }, label = { Text("내용") }, minLines = 8, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = !state.busy && !state.pendingSave,
                    onClick = { scope.launch { repository.save(document.field().copy(title = title, content = content), document.ocr, document)
                        .onSuccess { onBack() } } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("수정 저장") }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, BorderGray)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(document.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("내 자료", style = MaterialTheme.typography.labelSmall, color = SamsungBlue,
                                modifier = Modifier.background(SamsungBlueLight, MaterialTheme.shapes.extraLarge)
                                    .padding(horizontal = 7.dp, vertical = 3.dp))
                            Spacer(Modifier.weight(1f))
                            Text("등록일  ${DateFormat.getDateInstance().format(Date(document.created))}",
                                style = MaterialTheme.typography.bodySmall, color = Color(0xFF7E8795))
                        }
                    }
                }
                if (document.remoteState != PrivateRemoteState.AVAILABLE) {
                    Text(when (document.remoteState) {
                        PrivateRemoteState.TRASHED -> "최신 메타데이터가 Drive 휴지통에 있습니다. 원본은 자동 삭제하지 않습니다."
                        PrivateRemoteState.MISSING -> "메타데이터가 삭제되었거나 접근할 수 없습니다."
                        else -> "기존 자료의 최신 여부를 확인할 계보가 없습니다."
                    }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                val imageAttachments = document.attachments.filter { it.mime.startsWith("image/") }
                if (imageAttachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("원본 이미지", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
                            Spacer(Modifier.weight(1f))
                            Text(if (document.pinned) "고정 해제" else "자료 상단고정", style = MaterialTheme.typography.labelSmall,
                                color = if (document.pinned) SamsungBlue else Color(0xFF7E8795))
                            IconButton(onClick = { scope.launch { repository.togglePin(document) } },
                                enabled = !state.busy && !state.pendingSave && document.remoteState == PrivateRemoteState.AVAILABLE) {
                                Icon(Icons.Default.PushPin,
                                    contentDescription = if (document.pinned) "자료 상단 고정 해제" else "자료 상단 고정",
                                    tint = if (document.pinned) SamsungBlue else Color(0xFF7E8795))
                            }
                        }
                        imageAttachments.forEachIndexed { index, attachment ->
                            if (imageAttachments.size > 1) {
                                Text("페이지 ${index + 1}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF7E8795))
                            }
                            PrivateDetailImage(repository, attachment, document.title, onClick = {
                                scope.launch {
                                    repository.original(attachment).onSuccess { file -> onImageClick(Uri.fromFile(file).toString()) }
                                }
                            })
                        }
                        Text("이미지를 누르면 전체 화면에서 확대·축소할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF7E8795))
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("내용", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.weight(1f))
                        Text(if (document.pinned) "고정 해제" else "자료 상단고정", style = MaterialTheme.typography.labelSmall,
                            color = if (document.pinned) SamsungBlue else Color(0xFF7E8795))
                        IconButton(onClick = { scope.launch { repository.togglePin(document) } },
                            enabled = !state.busy && !state.pendingSave && document.remoteState == PrivateRemoteState.AVAILABLE) {
                            Icon(Icons.Default.PushPin,
                                contentDescription = if (document.pinned) "자료 상단 고정 해제" else "자료 상단 고정",
                                tint = if (document.pinned) SamsungBlue else Color(0xFF7E8795))
                        }
                    }
                    TextDocumentContentBody(document.content)
                }
                if (imageAttachments.isNotEmpty()) TextDocumentContent(document.content)
                document.attachments.filterNot { it.mime.startsWith("image/") }.forEachIndexed { index, attachment ->
                    TextButton(enabled = !state.busy, onClick = {
                        scope.launch {
                            repository.original(attachment).onSuccess { file ->
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    .onFailure { Toast.makeText(context, "이 첨부파일을 열 수 있는 앱이 필요합니다.", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }) { Text("첨부파일 ${index + 1} 열기") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PrivateDetailImage(
    repository: DriveConnectionRepository,
    attachment: PrivateAttachment,
    title: String,
    onClick: () -> Unit
) {
    val file by produceState<java.io.File?>(initialValue = null, key1 = attachment.id) {
        value = repository.thumbnail(attachment).getOrNull()
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BorderGray)
    ) {
        if (file == null) Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            PrivateThumbnailFallback()
        } else Box(modifier = Modifier.fillMaxWidth()) {
            SubcomposeAsyncImage(
                model = file,
                contentDescription = "$title 원본 이미지 전체 화면 보기",
                modifier = Modifier.fillMaxWidth().height(300.dp),
                contentScale = ContentScale.Fit,
                loading = { PrivateThumbnailFallback() },
                error = { PrivateThumbnailFallback() }
            )
            Surface(
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(12.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("전체 화면", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PrivateDocumentCard(repository: DriveConnectionRepository, document: PrivateDocument, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, BorderGray),
        onClick = onClick
    ) {
        val showPinnedIndicator = showsPinnedPrivateDocument(document)
        Box {
            Row(
                modifier = Modifier.padding(10.dp).padding(end = if (showPinnedIndicator) 24.dp else 0.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                PrivateThumbnail(document)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(document.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text(if (!document.detailsLoaded) "상세 정보를 불러오는 중…" else document.content.ifBlank { "첨부파일 ${document.attachments.size}개" },
                        style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.67f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(DateFormat.getDateInstance().format(Date(document.modified)),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A94A6))
                    if (document.syncStatus in setOf(PrivateSyncStatus.FAILED, PrivateSyncStatus.ACTION_REQUIRED)) {
                        Text(if (document.syncStatus == PrivateSyncStatus.FAILED) "동기화 실패 · 재시도" else "사용자 조치 필요",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { repository.retrySave() } }, contentPadding = PaddingValues(0.dp)) {
                            Text("동기화 재시도")
                        }
                    }
                }
            }
            if (showPinnedIndicator) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = "상단 고정된 자료",
                    tint = SamsungBlue.copy(alpha = 0.72f),
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(8.dp).size(18.dp)
                )
            }
        }
    }
}

internal fun privateSyncButtonLabel(state: DriveUiState): String =
    when {
        state.deleting -> "자료 삭제중"
        privateUploadInProgress(state) -> "드라이브 저장중"
        else -> "지금 동기화"
    }

internal fun privateSyncButtonEnabled(state: DriveUiState): Boolean =
    !state.busy && !state.syncing && !state.deleting && !privateUploadInProgress(state)

internal fun deleteRetryVisible(state: DriveUiState): Boolean =
    state.deleting && !state.deleteSyncError.isNullOrBlank()

private fun privateUploadInProgress(state: DriveUiState): Boolean =
    state.pendingSave && state.pendingSyncStatus in setOf(PrivateSyncStatus.WAITING, PrivateSyncStatus.SYNCING)

/** Navigation is deliberately synchronous; the durable deletion is then owned by the app scope. */
internal fun startPrivateDelete(navigateToList: () -> Unit, continueDeletion: () -> Unit) {
    navigateToList()
    continueDeletion()
}

/** A revision changes fileId; id is the stable logical-document identity used by the detail route. */
internal fun resolvePrivateDetailDocument(selected: PrivateDocument, heads: Collection<PrivateDocument>): PrivateDocument {
    val latest = heads.filter { candidate ->
        candidate.id == selected.id || (selected.lineageId.isNotBlank() && candidate.lineageId == selected.lineageId)
    }.maxByOrNull { it.modified } ?: return selected.copy(remoteState = PrivateRemoteState.MISSING)
    return when {
        latest.deleted -> latest.copy(remoteState = PrivateRemoteState.MISSING)
        latest.remoteState != PrivateRemoteState.AVAILABLE -> latest
        else -> latest
    }
}

@Composable
private fun PrivateThumbnail(document: PrivateDocument) {
    Surface(
        modifier = Modifier.size(width = 96.dp, height = 104.dp),
        shape = MaterialTheme.shapes.small,
        color = SamsungBlueLight
    ) {
        if (document.thumbnailUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = document.thumbnailUrl,
                contentDescription = "${document.title} 썸네일",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { PrivateThumbnailFallback() },
                error = { PrivateThumbnailFallback() }
            )
        } else {
            PrivateThumbnailFallback()
        }
    }
}

@Composable
private fun PrivateThumbnailFallback() = Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
    Icon(Icons.Default.Description, contentDescription = null, tint = SamsungBlue, modifier = Modifier.size(32.dp))
}

internal fun PrivateDocument.field() = FieldDocument(id, title, category, "", DocumentSource.TEXT, content,
    thumbnailColor = Color.White, icon = Icons.Default.Description)
