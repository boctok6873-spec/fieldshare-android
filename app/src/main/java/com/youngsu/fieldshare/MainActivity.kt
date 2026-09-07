package com.youngsu.fieldshare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import com.youngsu.fieldshare.ui.theme.BorderGray
import com.youngsu.fieldshare.ui.theme.FieldShareTheme
import com.youngsu.fieldshare.ui.theme.Ink
import com.youngsu.fieldshare.ui.theme.SamsungBlue
import com.youngsu.fieldshare.ui.theme.SamsungBlueDark
import com.youngsu.fieldshare.ui.theme.SamsungBlueLight
import com.youngsu.fieldshare.ui.theme.SoftGray
import com.youngsu.fieldshare.ui.theme.SuccessGreen
import java.time.LocalDate
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.firebase.auth.FirebaseAuth
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FieldShareTheme { FieldShareApp() }
        }
    }
}

@Composable
private fun FieldShareApp() {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var selectedCategory by rememberSaveable { mutableStateOf(AllCategory) }
    val context = LocalContext.current
    val settingsRepository = remember { AppSettingsRepository(context) }
    var homeDisplayMode by remember { mutableStateOf(settingsRepository.getHomeDisplayMode()) }
    val coroutineScope = rememberCoroutineScope()
    var authAttempt by rememberSaveable { mutableStateOf(0) }
    var authState by remember { mutableStateOf<FirebaseAuthUiState>(FirebaseAuthUiState.Loading) }

    LaunchedEffect(authAttempt) {
        authState = FirebaseAuthUiState.Loading
        authState = runCatching {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: auth.signInAnonymously().await().user
            checkNotNull(user?.uid) { "익명 로그인 사용자 정보를 받지 못했습니다." }
            FirebaseAuthUiState.Ready(user.uid)
        }.getOrElse { FirebaseAuthUiState.Error("익명 로그인에 실패했습니다. 네트워크와 Firebase Authentication 설정을 확인해 주세요.") }
    }

    when (val state = authState) {
        FirebaseAuthUiState.Loading -> FirebaseStartupState("Firebase에 로그인하는 중…")
        is FirebaseAuthUiState.Error -> FirebaseStartupState(state.message, onRetry = { authAttempt++ })
        is FirebaseAuthUiState.Ready -> UserProfileGate(
            currentUserId = state.uid,
            screen = screen,
            onScreenChange = { screen = it },
            selectedCategory = selectedCategory,
            onCategoryChange = { selectedCategory = it },
            context = context,
            coroutineScope = coroutineScope,
            homeDisplayMode = homeDisplayMode,
            onHomeDisplayModeChange = { mode ->
                settingsRepository.setHomeDisplayMode(mode)
                homeDisplayMode = mode
            }
        )
    }
}

@Composable
private fun UserProfileGate(
    currentUserId: String,
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    homeDisplayMode: HomeDocumentDisplayMode,
    onHomeDisplayModeChange: (HomeDocumentDisplayMode) -> Unit
) {
    val profileRepository = remember(currentUserId) { UserProfileRepository(currentUserId) }
    var profileAttempt by rememberSaveable { mutableStateOf(0) }
    var profileState by remember(currentUserId) { mutableStateOf<UserProfileGateState>(UserProfileGateState.Loading) }

    LaunchedEffect(currentUserId, profileAttempt) {
        profileState = UserProfileGateState.Loading
        profileState = profileRepository.getProfile().fold(
            onSuccess = { profile ->
                if (profile?.isRegistered == true) UserProfileGateState.Ready(profile)
                else UserProfileGateState.RegistrationRequired
            },
            onFailure = { error ->
                UserProfileGateState.Error(error.message ?: "사용자 정보를 불러오지 못했습니다.")
            }
        )
    }

    when (val state = profileState) {
        UserProfileGateState.Loading -> FirebaseStartupState("사용자 정보를 확인하는 중…")
        is UserProfileGateState.Error -> FirebaseStartupState(state.message, onRetry = { profileAttempt++ })
        UserProfileGateState.RegistrationRequired -> RequiredUserRegistrationScreen(
            onSave = { displayName, onSavingChange, onError ->
                coroutineScope.launch {
                    onSavingChange(true)
                    profileRepository.saveDisplayName(displayName)
                        .onSuccess { profile ->
                            onScreenChange(AppScreen.Home)
                            profileState = UserProfileGateState.Ready(profile)
                        }
                        .onFailure { error -> onError(error.message ?: "사용자 등록에 실패했습니다.") }
                    onSavingChange(false)
                }
            }
        )
        is UserProfileGateState.Ready -> FirebaseDocumentApp(
            currentUserId = currentUserId,
            currentProfile = state.profile,
            onProfileUpdated = { profile -> profileState = UserProfileGateState.Ready(profile) },
            screen = screen,
            onScreenChange = onScreenChange,
            selectedCategory = selectedCategory,
            onCategoryChange = onCategoryChange,
            context = context,
            coroutineScope = coroutineScope,
            homeDisplayMode = homeDisplayMode,
            onHomeDisplayModeChange = onHomeDisplayModeChange
        )
    }
}

@Composable
private fun FirebaseDocumentApp(
    currentUserId: String,
    currentProfile: UserProfile,
    onProfileUpdated: (UserProfile) -> Unit,
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    homeDisplayMode: HomeDocumentDisplayMode,
    onHomeDisplayModeChange: (HomeDocumentDisplayMode) -> Unit
) {
    val repository = remember(currentUserId, currentProfile.displayName) {
        FirebaseDocumentRepository(context.applicationContext, currentUserId, currentProfile.displayName)
    }
    val thumbnailUrlResolver = remember(repository) { repository::resolveThumbnailUrl }
    val profileRepository = remember(currentUserId) { UserProfileRepository(currentUserId) }
    val notificationManager = remember(currentUserId) {
        PushNotificationManager(context.applicationContext, currentUserId)
    }
    // Keep one listener/Flow for this repository instance; MetadataChanges.INCLUDE can otherwise
    // restart cache/server events whenever this composable recomposes.
    // Keep the document stream available for keyword searches even when the
    // default home thumbnail list is hidden. Visibility is decided below.
    val queryCategory = if (homeDisplayMode == HomeDocumentDisplayMode.ALL) AllCategory else selectedCategory
    val documentStream = remember(repository, homeDisplayMode, queryCategory) {
        repository.observeDocuments(
            category = queryCategory,
            displayMode = homeDisplayMode
        )
    }
    val stream by documentStream.collectAsState(initial = DocumentStream.Loading)
    val activityStream = remember(repository) { repository.observeActivities() }
    val activities by activityStream.collectAsState(initial = emptyList())
    val presenceRepository = remember(currentUserId) { FirebasePresenceRepository(context.applicationContext, currentUserId) }
    val presenceStream = remember(presenceRepository) { presenceRepository.observePresence() }
    val presence by presenceStream.collectAsState(initial = PresenceSummary())
    DisposableEffect(presenceRepository) {
        presenceRepository.start()
        onDispose { presenceRepository.stop() }
    }
    var isSaving by remember { mutableStateOf(false) }
    var isUpdatingText by remember { mutableStateOf(false) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var textUpdateError by remember { mutableStateOf<String?>(null) }
    var retryingPendingDeletionId by remember { mutableStateOf<String?>(null) }
    var pendingDeletionErrorDocumentId by remember { mutableStateOf<String?>(null) }
    var pendingDeletionErrorMessage by remember { mutableStateOf<String?>(null) }
    var locallyPendingDeletionIds by remember { mutableStateOf(emptySet<String>()) }
    var isProfileSaving by remember { mutableStateOf(false) }
    var profileSaveError by remember { mutableStateOf<String?>(null) }
    var notificationsEnabled by remember(notificationManager) {
        mutableStateOf(notificationManager.areNotificationsEnabled())
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchState by remember { mutableStateOf<DocumentSearchUiState>(DocumentSearchUiState.Idle) }
    var algoliaFailedQuery by remember { mutableStateOf<String?>(null) }
    var activeDocumentCount by remember { mutableStateOf<Long?>(null) }
    val documents = hidePendingDeletionDocuments(
        (stream as? DocumentStream.Data)?.documents.orEmpty(),
        locallyPendingDeletionIds
    )
    val allDocumentsLoadedFromServer = isAllDocumentsLoaded(homeDisplayMode, stream)
    val cachedDocumentStream = stream as? DocumentStream.Data
    val hasCachedDocuments = homeDisplayMode == HomeDocumentDisplayMode.ALL &&
        cachedDocumentStream?.isFromCache == true &&
        documents.isNotEmpty()
    val searchRoute = homeSearchRoute(
        homeDisplayMode = homeDisplayMode,
        isAllDocumentsLoaded = allDocumentsLoadedFromServer,
        hasAlgoliaFailedForCurrentQuery = algoliaFailedQuery == searchQuery.trim(),
        hasCachedDocuments = hasCachedDocuments
    )

    val enableNotifications = {
        coroutineScope.launch {
            notificationManager.enable()
                .onSuccess {
                    notificationsEnabled = true
                    operationError = null
                }
                .onFailure { error ->
                    operationError = error.message ?: "알림 설정을 저장하지 못했습니다."
                }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableNotifications()
        } else {
            operationError = "알림 권한을 허용하면 신규 자료 알림을 받을 수 있습니다."
        }
    }

    LaunchedEffect(notificationManager) {
        notificationManager.refreshRegistration().onFailure { error ->
            operationError = error.message ?: "알림 해제 정리를 다시 시도하지 못했습니다."
        }
    }

    LaunchedEffect(repository, screen is AppScreen.SyncStatus) {
        if (screen is AppScreen.SyncStatus) {
            activeDocumentCount = null
            repository.fetchActiveDocumentCount()
                .onSuccess { activeDocumentCount = it }
                .onFailure { error ->
                    Log.w("FieldShareSync", "활성 자료 수 집계 실패", error)
                }
        }
    }

    LaunchedEffect(
        repository,
        searchQuery,
        homeDisplayMode,
        allDocumentsLoadedFromServer,
        searchRoute,
        if (usesLocalHomeSearch(searchRoute, searchQuery)) documents else null
    ) {
        val normalizedQuery = searchQuery.trim()
        if (normalizedQuery.length < minimumHomeSearchQueryLength(searchRoute)) {
            searchState = DocumentSearchUiState.Idle
            return@LaunchedEffect
        }
        if (usesLocalHomeSearch(searchRoute, normalizedQuery)) {
            searchState = DocumentSearchUiState.Data(
                documents = searchLocalDocuments(documents, normalizedQuery),
                isOfflineCacheFallback = searchRoute == HomeSearchRoute.LOCAL_CACHE_FALLBACK
            )
            return@LaunchedEffect
        }
        delay(300)
        searchState = DocumentSearchUiState.Loading
        val result = repository.searchDocuments(normalizedQuery)
        currentCoroutineContext().ensureActive()
        searchState = result.fold(
            onSuccess = { DocumentSearchUiState.Data(it, hasUnresolvedImagePaths = true) },
            onFailure = {
                if (hasCachedDocuments && homeDisplayMode == HomeDocumentDisplayMode.ALL) {
                    algoliaFailedQuery = normalizedQuery
                    DocumentSearchUiState.Data(
                        documents = searchLocalDocuments(documents, normalizedQuery),
                        isOfflineCacheFallback = true
                    )
                } else {
                    DocumentSearchUiState.Error("검색 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.")
                }
            }
        )
    }
    val pendingDeletionDocuments = (stream as? DocumentStream.Data)?.pendingDeletionDocuments.orEmpty()
    val syncState = when (stream) {
        DocumentStream.Loading -> FirebaseSyncUiState.CONNECTING
        is DocumentStream.Data -> if ((stream as DocumentStream.Data).isFromCache) FirebaseSyncUiState.OFFLINE_CACHE else FirebaseSyncUiState.SYNCHRONIZED
        is DocumentStream.Error -> FirebaseSyncUiState.ERROR
    }
    val streamError = (stream as? DocumentStream.Error)?.message

    when (val currentScreen = screen) {
        AppScreen.Home -> FieldShareHomeScreen(
            documents = documents,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            searchState = searchState,
            homeDisplayMode = homeDisplayMode,
            selectedCategory = selectedCategory,
            onCategorySelected = onCategoryChange,
            onRegister = {
                if (selectedCategory == AllCategory) {
                    onCategoryChange(DefaultRegistrationCategory)
                }
                onScreenChange(AppScreen.Registration)
            },
            onSyncStatusClick = { onScreenChange(AppScreen.SyncStatus) },
            onMyProfileClick = { onScreenChange(AppScreen.MyProfile) },
            onSettingsClick = { onScreenChange(AppScreen.Settings) },
            onSoftwareInfoClick = { onScreenChange(AppScreen.SoftwareInfo) },
            notificationsEnabled = notificationsEnabled,
            onNotificationsClick = {
                if (notificationsEnabled) {
                    coroutineScope.launch {
                        notificationManager.disable()
                            .onSuccess {
                                notificationsEnabled = false
                                operationError = null
                            }
                            .onFailure { error ->
                                // Local opt-out is deliberately committed before remote cleanup.
                                notificationsEnabled = notificationManager.areNotificationsEnabled()
                                operationError = error.message ?: "알림 해제에 실패했습니다."
                            }
                    }
                } else if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    enableNotifications()
                }
            },
            onDocumentClick = { document ->
                coroutineScope.launch {
                    onScreenChange(AppScreen.Detail(repository.resolveImageUrls(document)))
                }
            },
            resolveThumbnailUrl = thumbnailUrlResolver,
            documentStream = stream,
            syncState = syncState,
            errorMessage = operationError ?: streamError,
        )
        AppScreen.Registration -> DocumentRegistrationScreen(
            selectedCategory = selectedCategory,
            onCategoryChange = onCategoryChange,
            onBack = { onScreenChange(AppScreen.Home) },
            isSaving = isSaving,
            saveError = operationError,
            onSave = { document, searchableText ->
                coroutineScope.launch {
                    isSaving = true
                    operationError = null
                    repository.create(
                        FirebaseDocumentUpload(
                            document = document,
                            searchableText = searchableText.orEmpty(),
                            imageUris = document.imageUris.ifEmpty { listOfNotNull(document.imageUri) },
                            pdfUri = document.pdfUri
                        )
                    ).onSuccess { saved ->
                        onScreenChange(AppScreen.Detail(saved))
                    }.onFailure { error ->
                        operationError = error.message ?: "자료 저장에 실패했습니다."
                    }
                    isSaving = false
                }
            }
        )
        is AppScreen.Detail -> DocumentDetailScreen(
            document = currentScreen.document,
            onBack = { onScreenChange(AppScreen.Home) },
            onImageClick = { imageUri -> onScreenChange(AppScreen.ImageViewer(currentScreen.document, imageUri)) },
            deleteError = operationError,
            onEdit = if (canEditDocumentText(currentScreen.document)) {
                { onScreenChange(AppScreen.EditTextDocument(currentScreen.document)) }
            } else null,
            onDelete = {
                    coroutineScope.launch {
                        operationError = null
                        repository.delete(
                            currentScreen.document,
                            onMarkedPendingDeletion = {
                                locallyPendingDeletionIds = locallyPendingDeletionIds + currentScreen.document.id
                                onScreenChange(AppScreen.Home)
                            }
                        )
                            .onFailure { operationError = it.message ?: "자료 삭제에 실패했습니다." }
                    }
            }
        )
        is AppScreen.ImageViewer -> FullScreenImageViewer(
            imageUri = currentScreen.imageUri,
            onClose = { onScreenChange(AppScreen.Detail(currentScreen.document)) }
        )
        is AppScreen.EditTextDocument -> TextDocumentEditScreen(
            document = currentScreen.document,
            isSaving = isUpdatingText,
            errorMessage = textUpdateError,
            onBack = { onScreenChange(AppScreen.Detail(currentScreen.document)) },
            onSave = { title, category, content ->
                coroutineScope.launch {
                    isUpdatingText = true
                    textUpdateError = null
                    repository.updateDocumentText(currentScreen.document, title, category, content)
                        .onSuccess { updated -> onScreenChange(AppScreen.Detail(updated)) }
                        .onFailure { error -> textUpdateError = error.message ?: "자료 수정에 실패했습니다." }
                    isUpdatingText = false
                }
            }
        )
        AppScreen.SyncStatus -> SyncStatusScreen(
            onBack = { onScreenChange(AppScreen.Home) },
            syncState = syncState,
            documentCount = activeDocumentCount,
            activities = activities,
            presence = presence,
            pendingDeletionDocuments = pendingDeletionDocuments,
            retryingPendingDeletionId = retryingPendingDeletionId,
            pendingDeletionErrorDocumentId = pendingDeletionErrorDocumentId,
            pendingDeletionErrorMessage = pendingDeletionErrorMessage,
            onRetryPendingDeletion = { document ->
                if (retryingPendingDeletionId == null) {
                    coroutineScope.launch {
                        retryingPendingDeletionId = document.id
                        pendingDeletionErrorDocumentId = null
                        pendingDeletionErrorMessage = null
                        repository.delete(document)
                            .onFailure { error ->
                                pendingDeletionErrorDocumentId = document.id
                                pendingDeletionErrorMessage = error.message ?: "자료 삭제 재시도에 실패했습니다."
                            }
                        retryingPendingDeletionId = null
                    }
                }
            }
        )
        AppScreen.MyProfile -> MyProfileScreen(
            displayName = currentProfile.displayName,
            isSaving = isProfileSaving,
            saveError = profileSaveError,
            onBack = { onScreenChange(AppScreen.Home) },
            onSave = { displayName ->
                coroutineScope.launch {
                    isProfileSaving = true
                    profileSaveError = null
                    profileRepository.saveDisplayName(displayName)
                        .onSuccess { profile ->
                            onProfileUpdated(profile)
                            onScreenChange(AppScreen.Home)
                        }
                        .onFailure { error -> profileSaveError = error.message ?: "내 정보 저장에 실패했습니다." }
                    isProfileSaving = false
                }
            }
        )
        AppScreen.Settings -> SettingsScreen(
            onBack = { onScreenChange(AppScreen.Home) },
            homeDisplayMode = homeDisplayMode,
            onHomeDisplayModeChange = onHomeDisplayModeChange
        )
        AppScreen.SoftwareInfo -> SoftwareInfoScreen(
            onBack = { onScreenChange(AppScreen.Home) }
        )
    }
}

private sealed interface FirebaseAuthUiState {
    data object Loading : FirebaseAuthUiState
    data class Ready(val uid: String) : FirebaseAuthUiState
    data class Error(val message: String) : FirebaseAuthUiState
}

private sealed interface UserProfileGateState {
    data object Loading : UserProfileGateState
    data object RegistrationRequired : UserProfileGateState
    data class Ready(val profile: UserProfile) : UserProfileGateState
    data class Error(val message: String) : UserProfileGateState
}

enum class FirebaseSyncUiState { CONNECTING, SYNCHRONIZED, OFFLINE_CACHE, ERROR }

sealed interface DocumentSearchUiState {
    data object Idle : DocumentSearchUiState
    data object Loading : DocumentSearchUiState
    data class Data(
        val documents: List<FieldDocument>,
        val isOfflineCacheFallback: Boolean = false,
        val hasUnresolvedImagePaths: Boolean = false
    ) : DocumentSearchUiState
    data class Error(val message: String) : DocumentSearchUiState
}

@Composable
private fun FirebaseStartupState(message: String, onRetry: (() -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize().background(SoftGray), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            if (onRetry == null) CircularProgressIndicator(color = SamsungBlue)
            else Icon(Icons.Default.Info, contentDescription = null, tint = SamsungBlue, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
            Text(message, textAlign = TextAlign.Center, color = Ink)
            if (onRetry != null) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onRetry) { Text("다시 시도") }
            }
        }
    }
}

@Composable
private fun BottomBackNavigationBar(onBack: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 화면")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequiredUserRegistrationScreen(
    onSave: (String, (Boolean) -> Unit, (String) -> Unit) -> Unit
) {
    var displayName by rememberSaveable { mutableStateOf("") }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var isSaving by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = true) { }

    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("사용자 등록", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SamsungBlue,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("자료 공유를 시작하려면 사용자 이름을 등록해 주세요.", color = Ink)
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    inputError = null
                    saveError = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("사용자 이름") },
                singleLine = true,
                isError = inputError != null,
                supportingText = {
                    Text(inputError ?: "공백을 제외하고 최대 20자")
                }
            )
            if (saveError != null) {
                Text(saveError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    if (UserProfileValidation.normalize(displayName) == null) {
                        inputError = "사용자 이름은 공백을 제외하고 1~20자로 입력해 주세요."
                    } else {
                        onSave(
                            displayName,
                            { isSaving = it },
                            { saveError = it }
                        )
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("저장")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyProfileScreen(
    displayName: String,
    isSaving: Boolean,
    saveError: String?,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    var editedName by rememberSaveable(displayName) { mutableStateOf(displayName) }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("내 정보", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SamsungBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = { BottomBackNavigationBar(onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("사용자 이름", fontWeight = FontWeight.Bold, color = Ink)
            OutlinedTextField(
                value = editedName,
                onValueChange = {
                    editedName = it
                    inputError = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = inputError != null,
                supportingText = { Text(inputError ?: "공백을 제외하고 최대 20자") }
            )
            if (saveError != null) {
                Text(saveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    if (UserProfileValidation.normalize(editedName) == null) {
                        inputError = "사용자 이름은 공백을 제외하고 1~20자로 입력해 주세요."
                    } else {
                        onSave(editedName)
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("저장")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    homeDisplayMode: HomeDocumentDisplayMode,
    onHomeDisplayModeChange: (HomeDocumentDisplayMode) -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("설정", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SamsungBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = { BottomBackNavigationBar(onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("홈 화면 자료 표시", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, BorderGray)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HomeDisplayModeOption(
                        title = "모든 자료 불러오기",
                        description = "등록일과 관계없이 모든 자료를 표시합니다.",
                        selected = homeDisplayMode == HomeDocumentDisplayMode.ALL,
                        onSelected = { onHomeDisplayModeChange(HomeDocumentDisplayMode.ALL) }
                    )
                    HomeDisplayModeOption(
                        title = "최근 등록 자료만 표시",
                        description = "카테고리별 최근 등록 순으로 표시합니다.",
                        selected = homeDisplayMode == HomeDocumentDisplayMode.RECENT_ONLY,
                        onSelected = { onHomeDisplayModeChange(HomeDocumentDisplayMode.RECENT_ONLY) }
                    )
                    HomeDisplayModeOption(
                        title = "자료 표시하지 않기",
                        description = "홈 화면에 자료 썸네일을 표시하지 않습니다.",
                        selected = homeDisplayMode == HomeDocumentDisplayMode.HIDDEN,
                        onSelected = { onHomeDisplayModeChange(HomeDocumentDisplayMode.HIDDEN) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeDisplayModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color(0xFF7E8795), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoftwareInfoScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("소프트웨어 정보", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SamsungBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = { BottomBackNavigationBar(onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Version : FieldShare_v1.0", color = Color(0xFF5E6878))
            Text("Developer : Kim Young-soo", color = Color(0xFF5E6878))
            Text("Development Tool : Android Studio", color = Color(0xFF5E6878))
            Text("Programming Language : Kotlin", color = Color(0xFF5E6878))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("UI Framework : Jetpack Compose", color = Color(0xFF5E6878))
                Text(
                    text = "(2026. 8. 31)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A94A6)
                )
            }
        }
    }
}

private sealed interface AppScreen {
    data object Home : AppScreen
    data object Registration : AppScreen
    data object SyncStatus : AppScreen
    data object MyProfile : AppScreen
    data object Settings : AppScreen
    data object SoftwareInfo : AppScreen
    data class Detail(val document: FieldDocument) : AppScreen
    data class ImageViewer(val document: FieldDocument, val imageUri: String?) : AppScreen
    data class EditTextDocument(val document: FieldDocument) : AppScreen
}

enum class DocumentSource { IMAGE, TEXT }

private const val AllCategory = "전체"
private const val DefaultRegistrationCategory = "기타"
private val registrationCategories = listOf("냉장고", "에어컨", "세탁기", "TV", "컴퓨터", "프린터", "자재", "기타")
private val homeCategories = listOf(AllCategory) + registrationCategories

data class FieldDocument(
    val id: String,
    val title: String,
    val category: String,
    val date: String,
    val source: DocumentSource,
    val content: String,
    val imageUri: String? = null,
    val imageUris: List<String> = emptyList(),
    val pdfUri: String? = null,
    val cloudDocumentId: String? = null,
    val ocrStatus: OcrStatus = OcrStatus.NOT_REQUESTED,
    val detail: String = "",
    val description: String = "",
    val thumbnailColor: Color,
    val icon: ImageVector,
    val searchableText: String = "",
    val createdBy: String = "",
    val createdByName: String = "익명 사용자",
    val createdAtMillis: Long? = null
)

internal fun hidePendingDeletionDocuments(
    documents: List<FieldDocument>,
    pendingDocumentIds: Set<String>
): List<FieldDocument> = documents.filterNot { it.id in pendingDocumentIds }

internal fun contentForDocumentSave(hasAttachment: Boolean, enteredContent: String): String =
    if (hasAttachment) enteredContent else enteredContent.ifBlank { "등록된 내용이 없습니다." }

internal fun activeDocumentCountLabel(documentCount: Long?): String =
    documentCount?.let { "${it}개" } ?: "—"

internal fun imageContentForDetail(content: String): String? = content
    .takeIf { it.isNotBlank() && it.trim() != "원본 이미지 자료" }

internal fun canEditDocumentText(document: FieldDocument): Boolean =
    document.source == DocumentSource.TEXT || imageContentForDetail(document.content) != null

internal fun scanPreparationFailureMessage(failedPageIndexes: List<Int>): String =
    "스캔 이미지 ${failedPageIndexes.joinToString(", ")}페이지를 처리하지 못했습니다. 다시 스캔하거나 재시도해 주세요."

internal fun limitHomeDocuments(
    documents: List<FieldDocument>,
    selectedCategory: String,
    displayMode: HomeDocumentDisplayMode
): List<FieldDocument> {
    val sortedDocuments = documents.sortedWith(
        homeDocumentComparator()
    )
    if (displayMode == HomeDocumentDisplayMode.HIDDEN) return emptyList()
    if (displayMode == HomeDocumentDisplayMode.ALL) return sortedDocuments
    val limit = if (selectedCategory == AllCategory) 10 else 5
    return sortedDocuments.take(limit)
}

internal fun homeDocumentsForDisplay(
    documents: List<FieldDocument>,
    selectedCategory: String,
    displayMode: HomeDocumentDisplayMode,
    hasSearchQuery: Boolean
): List<FieldDocument> {
    val categoryDocuments = documents.filter { selectedCategory == AllCategory || it.category == selectedCategory }
    return when {
        displayMode == HomeDocumentDisplayMode.HIDDEN && !hasSearchQuery -> emptyList()
        displayMode == HomeDocumentDisplayMode.HIDDEN -> categoryDocuments.sortedWith(homeDocumentComparator())
        else -> limitHomeDocuments(categoryDocuments, selectedCategory, displayMode)
    }
}

internal fun searchLocalDocuments(documents: List<FieldDocument>, query: String): List<FieldDocument> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return emptyList()
    return documents
        .filter { document ->
            listOf(
                document.title,
                document.category,
                document.detail,
                document.description,
                document.content,
                document.searchableText
            ).any { it.contains(normalizedQuery, ignoreCase = true) }
        }
        .sortedWith(homeDocumentComparator())
}

private fun homeDocumentComparator(): Comparator<FieldDocument> =
    compareByDescending<FieldDocument> { it.createdAtMillis ?: Long.MIN_VALUE }
        .thenByDescending { it.date }
        .thenByDescending { it.id }

internal enum class HomeSearchRoute {
    LOCAL_ALL_DOCUMENTS,
    ALGOLIA,
    LOCAL_CACHE_FALLBACK
}

internal fun isAllDocumentsLoaded(
    displayMode: HomeDocumentDisplayMode,
    stream: DocumentStream
): Boolean = displayMode == HomeDocumentDisplayMode.ALL &&
    stream is DocumentStream.Data &&
    !stream.isFromCache

internal fun homeSearchRoute(
    homeDisplayMode: HomeDocumentDisplayMode,
    isAllDocumentsLoaded: Boolean,
    hasAlgoliaFailedForCurrentQuery: Boolean = false,
    hasCachedDocuments: Boolean = false
): HomeSearchRoute = when {
    homeDisplayMode != HomeDocumentDisplayMode.ALL -> HomeSearchRoute.ALGOLIA
    isAllDocumentsLoaded -> HomeSearchRoute.LOCAL_ALL_DOCUMENTS
    hasAlgoliaFailedForCurrentQuery && hasCachedDocuments -> HomeSearchRoute.LOCAL_CACHE_FALLBACK
    else -> HomeSearchRoute.ALGOLIA
}

internal fun minimumHomeSearchQueryLength(searchRoute: HomeSearchRoute): Int =
    if (searchRoute == HomeSearchRoute.ALGOLIA) 2 else 1

internal fun homeSearchMinimumQueryMessage(
    homeDisplayMode: HomeDocumentDisplayMode,
    searchRoute: HomeSearchRoute
): String = if (homeDisplayMode == HomeDocumentDisplayMode.ALL && searchRoute == HomeSearchRoute.ALGOLIA) {
    "전체 자료 검색은 2글자 이상 입력해 주세요."
} else {
    "검색어를 ${minimumHomeSearchQueryLength(searchRoute)}글자 이상 입력해 주세요."
}

internal fun usesLocalHomeSearch(searchRoute: HomeSearchRoute, query: String): Boolean =
    searchRoute != HomeSearchRoute.ALGOLIA &&
        query.trim().length >= minimumHomeSearchQueryLength(searchRoute)

private val sampleDocuments = listOf(
    FieldDocument("aircon-c422", "에어컨 C422 조치방법", "에어컨", "2026-08-23", DocumentSource.TEXT, "C422 에러 발생 시 점검 및 조치 방법 안내\n\n1. 전원 차단 후 5분 이상 대기\n2. 실외기 통신 배선 연결 상태 확인\n3. 이상이 계속되면 서비스 점검 요청", detail = "C422", description = "C422 에러 발생 시 점검 및 조치 방법 안내", thumbnailColor = Color(0xFFE5F2FF), icon = Icons.Default.Thermostat),
    FieldDocument("washer-ue", "세탁기 UE 점검", "세탁기", "2026-08-22", DocumentSource.TEXT, "UE 에러 확인 및 수평·빨래량 점검 방법", detail = "UE", description = "UE 에러 확인 및 수평·빨래량 점검 방법", thumbnailColor = Color(0xFFEAF7F2), icon = Icons.AutoMirrored.Filled.Article),
    FieldDocument("fridge-cooling", "냉장고 냉기 약함", "냉장고", "2026-08-21", DocumentSource.TEXT, "냉기 약함 증상 원인 및 조치 방법", detail = "냉기", description = "냉기 약함 증상 원인 및 조치 방법", thumbnailColor = Color(0xFFFFF2E5), icon = Icons.Default.WbSunny),
    FieldDocument("aircon-noise", "에어컨 실외기 소음", "에어컨", "2026-08-20", DocumentSource.TEXT, "실외기 소음 발생 시 확인 사항", detail = "소음", description = "실외기 소음 발생 시 확인 사항", thumbnailColor = Color(0xFFF2EEFF), icon = Icons.Default.Thermostat),
    FieldDocument("tv-image", "TV 화면 잔상 점검", "TV", "2026-08-19", DocumentSource.TEXT, "화면 잔상 및 색 번짐 확인 절차", detail = "화면", description = "화면 잔상 및 색 번짐 확인 절차", thumbnailColor = Color(0xFFE9F6FA), icon = Icons.Default.Tv),
    FieldDocument("fridge-ice", "냉장고 제빙기 작동 불량", "냉장고", "2026-08-18", DocumentSource.TEXT, "제빙기 작동 불량 시 기본 진단 순서", detail = "부품", description = "제빙기 작동 불량 시 기본 진단 순서", thumbnailColor = Color(0xFFFCEEF2), icon = Icons.AutoMirrored.Filled.Article)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldShareHomeScreen(
    documents: List<FieldDocument> = sampleDocuments,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchState: DocumentSearchUiState = DocumentSearchUiState.Idle,
    ocrSearchTextForDocument: (String) -> String = { "" },
    selectedCategory: String = AllCategory,
    onCategorySelected: (String) -> Unit = {},
    onRegister: () -> Unit = {},
    onSyncStatusClick: () -> Unit = {},
    onMyProfileClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSoftwareInfoClick: () -> Unit = {},
    notificationsEnabled: Boolean = false,
    onNotificationsClick: () -> Unit = {},
    onDocumentClick: (FieldDocument) -> Unit = {},
    resolveThumbnailUrl: suspend (String) -> String? = { imagePath -> imagePath },
    homeDisplayMode: HomeDocumentDisplayMode = HomeDocumentDisplayMode.RECENT_ONLY,
    documentStream: DocumentStream = DocumentStream.Data(documents, false),
    syncState: FirebaseSyncUiState = FirebaseSyncUiState.SYNCHRONIZED,
    errorMessage: String? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasSearchQuery = searchQuery.isNotBlank()
    val homeDocuments = homeDocumentsForDisplay(documents, selectedCategory, homeDisplayMode, false)
    val allDocumentsLoaded = isAllDocumentsLoaded(homeDisplayMode, documentStream)
    val currentSearchRoute = homeSearchRoute(
        homeDisplayMode = homeDisplayMode,
        isAllDocumentsLoaded = allDocumentsLoaded
    )
    val minimumSearchQueryLength = minimumHomeSearchQueryLength(currentSearchRoute)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SoftGray,
        topBar = {
            Column(
                modifier = Modifier.background(Brush.verticalGradient(listOf(SamsungBlueDark, SamsungBlue)))
            ) {
                CenterAlignedTopAppBar(
                    title = { Text("삼성서비스 자료공유", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.Menu, contentDescription = "메뉴")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("내 정보") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = "내 정보") },
                                    onClick = {
                                        menuExpanded = false
                                        onMyProfileClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("공유 현황") },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = "공유 현황") },
                                    onClick = {
                                        menuExpanded = false
                                        onSyncStatusClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("설정") },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = "설정") },
                                    onClick = {
                                        menuExpanded = false
                                        onSettingsClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("소프트웨어 정보") },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = "소프트웨어 정보") },
                                    onClick = {
                                        menuExpanded = false
                                        onSoftwareInfoClick()
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onSyncStatusClick) {
                            Icon(Icons.Default.Sync, contentDescription = "공유 현황")
                        }
                        IconButton(onClick = onNotificationsClick) {
                            Icon(
                                imageVector = if (notificationsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                                contentDescription = if (notificationsEnabled) "신규 자료 알림 켜짐" else "신규 자료 알림 켜기"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
                SearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp)
                )
                CategoryTabs(
                    categories = homeCategories,
                    selectedCategory = selectedCategory,
                    onCategorySelected = onCategorySelected,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onRegister,
                shape = RoundedCornerShape(18.dp),
                containerColor = SamsungBlue,
                contentColor = Color.White
            ) {
                Row(modifier = Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = "자료 등록")
                    Spacer(Modifier.width(4.dp))
                    Text("등록", fontWeight = FontWeight.Bold)
                }
            }
        },
        bottomBar = { SyncStatusBar(syncState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "최신 등록순",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink.copy(alpha = 0.72f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            when {
                hasSearchQuery && searchQuery.trim().length < minimumSearchQueryLength -> item {
                    FirebaseListMessage(homeSearchMinimumQueryMessage(homeDisplayMode, currentSearchRoute))
                }
                hasSearchQuery -> when (searchState) {
                    DocumentSearchUiState.Idle, DocumentSearchUiState.Loading -> item {
                        FirebaseListMessage("검색 결과를 불러오는 중…", loading = true)
                    }
                    is DocumentSearchUiState.Error -> item {
                        FirebaseListMessage(searchState.message)
                    }
                    is DocumentSearchUiState.Data -> {
                        if (searchState.isOfflineCacheFallback) {
                            item {
                                Text(
                                    "현재 기기에 저장된 자료에서 검색한 결과입니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF7E8795)
                                )
                            }
                        }
                        if (searchState.documents.isEmpty()) {
                            item { FirebaseListMessage("검색 조건에 맞는 자료가 없습니다.") }
                        } else {
                            items(searchState.documents, key = { it.id }) { document ->
                                DocumentCard(
                                    document,
                                    resolveThumbnailUrl = resolveThumbnailUrl,
                                    onClick = { onDocumentClick(document) }
                                )
                            }
                        }
                    }
                }
                else -> when (documentStream) {
                    DocumentStream.Loading -> item { FirebaseListMessage("Firebase 자료를 불러오는 중…", loading = true) }
                    is DocumentStream.Error -> item { FirebaseListMessage(errorMessage ?: documentStream.message) }
                    is DocumentStream.Data -> when {
                        !errorMessage.isNullOrBlank() -> item { FirebaseListMessage(errorMessage) }
                        homeDisplayMode == HomeDocumentDisplayMode.HIDDEN -> item {
                            FirebaseListMessage("홈 화면 자료 표시가 꺼져 있습니다. 설정에서 변경할 수 있습니다.")
                        }
                        homeDocuments.isEmpty() -> item {
                            FirebaseListMessage("등록된 자료가 없습니다. 오른쪽 아래 등록 버튼으로 첫 자료를 추가해 주세요.")
                        }
                        else -> items(homeDocuments, key = { it.id }) { document ->
                            DocumentCard(
                                document,
                                resolveThumbnailUrl = resolveThumbnailUrl,
                                onClick = { onDocumentClick(document) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingDeletionSection(
    documents: List<FieldDocument>,
    retryingDocumentId: String?,
    errorDocumentId: String?,
    errorMessage: String?,
    onRetry: (FieldDocument) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "삭제 재시도 필요",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFB3261E),
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "첨부 파일 정리가 완료되지 않은 자료입니다. 일반 목록과 검색에서는 제외됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7E8795)
        )
        documents.forEach { document ->
            val isRetrying = retryingDocumentId == document.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8F7)),
                border = BorderStroke(1.dp, Color(0xFFFFDAD6))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(document.title, fontWeight = FontWeight.Bold, color = Ink)
                    Text(document.category, style = MaterialTheme.typography.labelMedium, color = Color(0xFF7E8795))
                    if (errorDocumentId == document.id && !errorMessage.isNullOrBlank()) {
                        Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB3261E))
                    }
                    Button(
                        onClick = { onRetry(document) },
                        enabled = retryingDocumentId == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                    ) {
                        if (isRetrying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("삭제 재시도")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirebaseListMessage(message: String, loading: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(28.dp), color = SamsungBlue, strokeWidth = 3.dp)
            else Icon(Icons.Default.Info, contentDescription = null, tint = SamsungBlue)
            Text(message, textAlign = TextAlign.Center, color = Ink)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncStatusScreen(
    onBack: () -> Unit,
    syncState: FirebaseSyncUiState,
    documentCount: Long?,
    activities: List<DocumentActivity>,
    presence: PresenceSummary,
    pendingDeletionDocuments: List<FieldDocument> = emptyList(),
    retryingPendingDeletionId: String? = null,
    pendingDeletionErrorDocumentId: String? = null,
    pendingDeletionErrorMessage: String? = null,
    onRetryPendingDeletion: (FieldDocument) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    var showAllActivities by rememberSaveable { mutableStateOf(false) }
    val isSyncing = syncState == FirebaseSyncUiState.CONNECTING

    if (showAllActivities) {
        AlertDialog(
            onDismissRequest = { showAllActivities = false },
            title = { Text("최근 30일 활동 전체", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (activities.isEmpty()) {
                        Text("최근 30일 내 기록된 활동이 없습니다.", color = Color(0xFF7E8795))
                    } else {
                        activities.forEach { activity ->
                            ActivityListRow(activity)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAllActivities = false }) { Text("닫기") }
            }
        )
    }

    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("공유 현황", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {}, enabled = false) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "동기화 새로고침")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF078E95),
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = { BottomBackNavigationBar(onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SyncStatusCard(syncState = syncState)
            SyncSummaryGrid(syncState = syncState, documentCount = documentCount, presence = presence)
            if (pendingDeletionDocuments.isNotEmpty()) {
                PendingDeletionSection(
                    documents = pendingDeletionDocuments,
                    retryingDocumentId = retryingPendingDeletionId,
                    errorDocumentId = pendingDeletionErrorDocumentId,
                    errorMessage = pendingDeletionErrorMessage,
                    onRetry = onRetryPendingDeletion
                )
            }
            RecentSyncActivitySection(activities = activities.take(5), onShowAll = { showAllActivities = true })
            ConnectedDeviceSection(presence)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SyncStatusCard(syncState: FirebaseSyncUiState) {
    val isSyncing = syncState == FirebaseSyncUiState.CONNECTING
    val title = when (syncState) {
        FirebaseSyncUiState.SYNCHRONIZED -> "Firebase와 실시간 동기화됨"
        FirebaseSyncUiState.OFFLINE_CACHE -> "오프라인 캐시를 표시 중"
        FirebaseSyncUiState.ERROR -> "Firebase 동기화 오류"
        FirebaseSyncUiState.CONNECTING -> "Firebase 연결 중"
    }
    val subtitle = when (syncState) {
        FirebaseSyncUiState.SYNCHRONIZED -> "Firestore 변경 사항을 실시간으로 수신하고 있습니다."
        FirebaseSyncUiState.OFFLINE_CACHE -> "네트워크가 복구되면 최신 자료와 다시 동기화됩니다."
        FirebaseSyncUiState.ERROR -> "네트워크와 Firebase 보안 규칙을 확인해 주세요."
        FirebaseSyncUiState.CONNECTING -> "문서 목록을 확인하고 있습니다."
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F8F2)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(if (isSyncing) Color(0xFF0A969C) else SuccessGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(21.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = "동기화 완료", tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isSyncing) Color(0xFF087D82) else Color(0xFF24712E)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6F7C76)
                )
            }
        }
    }
}

@Composable
private fun SyncSummaryGrid(
    syncState: FirebaseSyncUiState,
    documentCount: Long?,
    presence: PresenceSummary
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SyncSummaryCard(
                title = "총 자료",
                value = activeDocumentCountLabel(documentCount),
                caption = "Firebase 서버",
                icon = Icons.Default.Folder,
                iconTint = Color(0xFF1460D8),
                iconBackground = Color(0xFFEAF2FF),
                modifier = Modifier.weight(1f)
            )
            SyncSummaryCard(
                title = "접속 중",
                value = "${presence.onlineUserCount}명",
                caption = "Presence 기준",
                icon = Icons.Default.Group,
                iconTint = Color(0xFF1460D8),
                iconBackground = Color(0xFFEAF2FF),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SyncSummaryCard(
                title = "마지막 동기화",
                value = when (syncState) {
                    FirebaseSyncUiState.SYNCHRONIZED -> "연결됨"
                    FirebaseSyncUiState.OFFLINE_CACHE -> "오프라인"
                    FirebaseSyncUiState.ERROR -> "오류"
                    FirebaseSyncUiState.CONNECTING -> "연결 중"
                },
                caption = "Firestore 상태",
                icon = Icons.Default.CloudSync,
                iconTint = Color(0xFF078E95),
                iconBackground = Color(0xFFE4F6F6),
                modifier = Modifier.weight(1f)
            )
            SyncSummaryCard(
                title = "연결 디바이스",
                value = "${presence.onlineDeviceCount}대",
                caption = "Presence 기준",
                icon = Icons.Default.Devices,
                iconTint = Color(0xFF078E95),
                iconBackground = Color(0xFFE4F6F6),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SyncSummaryCard(
    title: String,
    value: String,
    caption: String,
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(iconBackground, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF5F6877))
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
                Text(caption, style = MaterialTheme.typography.labelSmall, color = Color(0xFF7E8795), maxLines = 1)
            }
        }
    }
}

@Composable
private fun RecentSyncActivitySection(activities: List<DocumentActivity>, onShowAll: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("최근 활동", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onShowAll, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text("전체 보기 >", style = MaterialTheme.typography.labelMedium, color = SamsungBlue)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (activities.isEmpty()) {
                    Text(
                        "최근 30일 내 기록된 활동이 없습니다.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = Color(0xFF7E8795),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                activities.forEachIndexed { index, activity ->
                    ActivityTimelineItem(
                        activity = activity,
                        showConnector = index != activities.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityTimelineItem(activity: DocumentActivity, showConnector: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.width(40.dp).height(54.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(31.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0xFFE1E7EE))
                )
            }
            Box(
                modifier = Modifier.size(28.dp).background(activityColor(activity).copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(activityIcon(activity), contentDescription = activityTitle(activity), tint = activityColor(activity), modifier = Modifier.size(16.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(activityTitle(activity), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(activity.documentTitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF7E8795))
        }
        Text(activityDate(activity), style = MaterialTheme.typography.labelSmall, color = Color(0xFF7E8795))
    }
}

@Composable
private fun ActivityListRow(activity: DocumentActivity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(activityIcon(activity), contentDescription = null, tint = activityColor(activity))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(activityTitle(activity), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(activity.documentTitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF7E8795))
        }
        Text(activityDate(activity), style = MaterialTheme.typography.labelSmall, color = Color(0xFF7E8795))
    }
}

private fun activityTitle(activity: DocumentActivity): String = when (activity.action) {
    DocumentActivityAction.CREATED -> "${activity.actorLabel}님이 자료 등록"
    DocumentActivityAction.UPDATED -> "${activity.actorLabel}님이 자료 수정"
    DocumentActivityAction.DELETED -> "${activity.actorLabel}님이 자료 삭제"
}

private fun activityIcon(activity: DocumentActivity): ImageVector = when (activity.action) {
    DocumentActivityAction.CREATED -> Icons.Default.Add
    DocumentActivityAction.UPDATED -> Icons.Default.Edit
    DocumentActivityAction.DELETED -> Icons.Default.Delete
}

private fun activityColor(activity: DocumentActivity): Color = when (activity.action) {
    DocumentActivityAction.CREATED -> SamsungBlue
    DocumentActivityAction.UPDATED -> Color(0xFF7848D8)
    DocumentActivityAction.DELETED -> Color(0xFFB3261E)
}

internal fun activityDate(activity: DocumentActivity): String = activity.createdAt?.toDate()
    ?.toInstant()
    ?.atZone(java.time.ZoneId.of("Asia/Seoul"))
    ?.toLocalDate()
    ?.format(java.time.format.DateTimeFormatter.ofPattern("M월 d일"))
    ?: "방금 전"

@Composable
private fun ConnectedDeviceSection(presence: PresenceSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("연결 기기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Ink)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (presence.onlineDeviceCount == 0) {
                    Text("현재 연결된 기기가 없습니다.", color = Color(0xFF7E8795), style = MaterialTheme.typography.bodySmall)
                } else {
                    presence.deviceTypeCounts.toSortedMap().forEach { (deviceType, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(deviceTypeIcon(deviceType), contentDescription = null, tint = SamsungBlue)
                            Spacer(Modifier.width(10.dp))
                            Text(deviceTypeLabel(deviceType), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text("${count}대", color = Color(0xFF5F6877))
                        }
                    }
                }
            }
        }
    }
}

private fun deviceTypeIcon(deviceType: String): ImageVector = when (deviceType) {
    "tablet" -> Icons.Default.TabletAndroid
    "desktop" -> Icons.Default.DesktopWindows
    else -> Icons.Default.PhoneAndroid
}

private fun deviceTypeLabel(deviceType: String): String = when (deviceType) {
    "tablet" -> "태블릿"
    "desktop" -> "데스크톱"
    "phone" -> "휴대폰"
    else -> "기타 기기"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentRegistrationScreen(
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: (FieldDocument, String?) -> Unit,
    isSaving: Boolean = false,
    saveError: String? = null
) {
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var selectedImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var scannedImageUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentPreparationError by rememberSaveable { mutableStateOf<String?>(null) }
    var isImageOptimizing by rememberSaveable { mutableStateOf(false) }
    var isOcrExtracting by rememberSaveable { mutableStateOf(false) }
    var ocrStatus by rememberSaveable { mutableStateOf(OcrStatus.NOT_REQUESTED) }
    var ocrSearchText by remember { mutableStateOf<String?>(null) }
    var titleEditedByUser by rememberSaveable { mutableStateOf(false) }
    var titleAutofilled by rememberSaveable { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    val categoryInteractionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(pendingCameraFilePath) {
        onDispose {
            pendingCameraFilePath?.let(::File)?.delete()
        }
    }
    val extractOcr: (List<Uri>, String) -> Unit = { imageUris, completionNotice ->
        isOcrExtracting = true
        ocrStatus = OcrStatus.PENDING
        selectionNotice = "이미지에서 검색 정보를 추출하는 중…"
        coroutineScope.launch {
            val ocrResult = withContext(Dispatchers.IO) {
                runCatching { OcrTextExtractor.extract(context, imageUris) }
            }
            val extraction = ocrResult.getOrNull()
            val extractedText = extraction?.searchableText
            if (!extractedText.isNullOrBlank()) {
                ocrSearchText = extractedText
                ocrStatus = OcrStatus.COMPLETED
                autoTitleIfEligible(
                    currentTitle = title,
                    titleEditedByUser = titleEditedByUser,
                    candidate = selectOcrTitleCandidate(extraction.topTextLinesByImage)
                )?.let { candidate ->
                        title = candidate
                        titleAutofilled = true
                    }
                selectionNotice = completionNotice
            } else {
                ocrSearchText = null
                ocrStatus = OcrStatus.FAILED
                selectionNotice = "검색용 텍스트를 추출하지 못했습니다."
            }
            isOcrExtracting = false
        }
    }
    val prepareImages: (List<Uri>, (List<Uri>) -> Unit, (Int) -> String, () -> Unit) -> Unit = { sourceUris, onPrepared, completionNotice, onSourcesFinished ->
        isImageOptimizing = true
        attachmentPreparationError = null
        ocrSearchText = null
        ocrStatus = OcrStatus.PENDING
        selectionNotice = "이미지 최적화 중…"
        coroutineScope.launch {
            try {
                val optimizedImages = withContext(Dispatchers.IO) {
                    optimizeImageUrisToFileProviderUris(
                        context = context,
                        sourceUris = sourceUris,
                        cachePrefix = "image",
                        logTag = "FieldShareImage"
                    )
                }
                val preparedUris = optimizedUrisOrNull(optimizedImages)
                if (preparedUris == null) {
                    isImageOptimizing = false
                    ocrStatus = OcrStatus.FAILED
                    attachmentPreparationError = imagePreparationFailureMessage(
                        optimizedImages.mapIndexedNotNull { index, result -> (index + 1).takeIf { result.isFailure } }
                    )
                    selectionNotice = attachmentPreparationError
                    return@launch
                }
                onPrepared(preparedUris)
                isImageOptimizing = false
                extractOcr(preparedUris, completionNotice(preparedUris.size))
            } finally {
                onSourcesFinished()
            }
        }
    }
    val prepareScannedImages: (List<Uri>) -> Unit = { scannerUris ->
        isImageOptimizing = true
        attachmentPreparationError = null
        ocrSearchText = null
        ocrStatus = OcrStatus.PENDING
        selectionNotice = "스캔 이미지 최적화 중…"
        coroutineScope.launch {
            val optimizedPages = withContext(Dispatchers.IO) {
                optimizeImageUrisToFileProviderUris(
                    context = context,
                    sourceUris = scannerUris,
                    cachePrefix = "scan",
                    logTag = "FieldShareScan"
                )
            }
            val failedPageIndexes = optimizedPages.mapIndexedNotNull { index, result ->
                (index + 1).takeIf { result.isFailure }
            }
            if (failedPageIndexes.isNotEmpty()) {
                isImageOptimizing = false
                ocrStatus = OcrStatus.FAILED
                attachmentPreparationError = scanPreparationFailureMessage(failedPageIndexes)
                selectionNotice = attachmentPreparationError
                return@launch
            }
            val preparedUris = optimizedUrisOrNull(optimizedPages) ?: return@launch
            selectedImageUri = preparedUris.first().toString()
            scannedImageUris = preparedUris.map(Uri::toString)
            isImageOptimizing = false
            extractOcr(preparedUris, "문서 ${preparedUris.size}페이지를 스캔했습니다.")
        }
    }
    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val documentScanner = remember(scannerOptions) {
        GmsDocumentScanning.getClient(scannerOptions)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            selectionNotice = "갤러리 선택이 취소되었습니다."
        } else {
            prepareImages(listOf(uri), { preparedUris ->
                selectedImageUri = preparedUris.first().toString()
                scannedImageUris = emptyList()
            }, { "고화질 이미지로 등록 준비가 완료되었습니다." }, {})
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val capturedFile = pendingCameraFilePath?.let(::File)
        val capturedUri = capturedFile?.let { file ->
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (error: Throwable) {
                Log.e(
                    "FieldShareImage",
                    "카메라 이미지 URI 생성 실패: ${error.javaClass.simpleName}: ${error.message}",
                    error
                )
                null
            }
        }
        if (saved && capturedUri != null) {
            prepareImages(listOf(capturedUri), { preparedUris ->
                selectedImageUri = preparedUris.first().toString()
                scannedImageUris = emptyList()
            }, { "고화질 이미지로 등록 준비가 완료되었습니다." }, {
                capturedFile?.delete()
                pendingCameraFilePath = null
            })
        } else {
            selectionNotice = "촬영이 취소되었습니다."
            capturedFile?.delete()
            pendingCameraFilePath = null
        }
    }
    val documentScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) {
            selectionNotice = "문서 스캔이 취소되었습니다."
            return@rememberLauncherForActivityResult
        }

        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
        val imageUris = scanResult?.pages?.map { it.imageUri.toString() }.orEmpty()
        if (imageUris.isEmpty()) {
            selectionNotice = "스캔 이미지 결과를 찾지 못했습니다."
            return@rememberLauncherForActivityResult
        }
        prepareScannedImages(imageUris.map(Uri::parse))
    }

    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("자료 등록", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SamsungBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = { BottomBackNavigationBar(onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RegisterMethodCard(
                    title = "문서 스캔",
                    description = "서류 자동 보정 스캔",
                    icon = Icons.Default.DocumentScanner,
                    iconColor = Color(0xFF087A82),
                    background = Color(0xFFDCEFF2),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!isImageOptimizing && !isOcrExtracting) {
                            attachmentPreparationError = null
                            val activity = context.findActivity()
                            if (activity == null) {
                                selectionNotice = "문서 스캔 화면을 열지 못했습니다. 다시 시도해 주세요."
                            } else {
                                documentScanner.getStartScanIntent(activity)
                                    .addOnSuccessListener { intentSender ->
                                        documentScannerLauncher.launch(
                                            IntentSenderRequest.Builder(intentSender).build()
                                        )
                                    }
                                    .addOnFailureListener {
                                        selectionNotice = "문서 스캐너를 실행하지 못했습니다. 다시 시도해 주세요."
                                    }
                                }
                            }
                    }
                )
                RegisterMethodCard(
                    title = "카메라 촬영",
                    description = "사진 직접 촬영",
                    icon = Icons.Default.CameraAlt,
                    iconColor = SamsungBlue,
                    background = Color(0xFFDDEAFE),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!isImageOptimizing && !isOcrExtracting) {
                            val cameraFile = createCameraCacheFile(context)
                            if (cameraFile != null) {
                                pendingCameraFilePath = cameraFile.absolutePath
                                cameraLauncher.launch(
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        cameraFile
                                    )
                                )
                            } else {
                                selectionNotice = "카메라를 준비하지 못했습니다. 다시 시도해 주세요."
                            }
                        }
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RegisterMethodCard(
                    title = "갤러리 선택",
                    description = "저장된 이미지 선택",
                    icon = Icons.Default.Image,
                    iconColor = Color(0xFF15844E),
                    background = Color(0xFFDEF1E4),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!isImageOptimizing && !isOcrExtracting) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                )
                RegisterMethodCard(
                    title = "직접 입력",
                    description = "텍스트로 직접 입력",
                    icon = Icons.Default.Edit,
                    iconColor = Color(0xFF6B43D0),
                    background = Color(0xFFEAE4FA),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!isImageOptimizing && !isOcrExtracting) {
                            selectedImageUri = null
                            scannedImageUris = emptyList()
                            ocrSearchText = null
                            ocrStatus = OcrStatus.NOT_REQUESTED
                            titleAutofilled = false
                            selectionNotice = "직접 입력 자료로 등록합니다."
                        }
                    }
                )
            }

            if (selectionNotice != null) {
                Text(
                    text = selectionNotice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedImageUri != null) SuccessGreen else Color(0xFF7E8795),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (!saveError.isNullOrBlank()) {
                Text(
                    text = saveError,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB3261E),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (selectedImageUri != null) {
                RegistrationImagePreview(selectedImageUri)
            }

            Spacer(Modifier.height(2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RegistrationLabel("제목")
                RegistrationTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        titleEditedByUser = true
                        titleAutofilled = false
                    },
                    placeholder = "제목을 입력하세요",
                    compact = true
                )
                if (titleAutofilled) {
                    Text(
                        "이미지에서 제목을 불러왔습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RegistrationLabel("카테고리")
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = !categoryMenuExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicTextField(
                        value = selectedCategory,
                        onValueChange = { },
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(45.dp)
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        interactionSource = categoryInteractionSource,
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = selectedCategory,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = categoryInteractionSource,
                                placeholder = {
                                    Text("선택하세요", color = Color(0xFF9BA3B1), style = MaterialTheme.typography.bodySmall)
                                },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White,
                                    focusedBorderColor = SamsungBlue,
                                    unfocusedBorderColor = Color(0xFFD9DEE7)
                                ),
                                contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                                container = {
                                    OutlinedTextFieldDefaults.Container(
                                        enabled = true,
                                        isError = false,
                                        interactionSource = categoryInteractionSource,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White,
                                            unfocusedContainerColor = Color.White,
                                            focusedBorderColor = SamsungBlue,
                                            unfocusedBorderColor = Color(0xFFD9DEE7)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            )
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        registrationCategories.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    onCategoryChange(item)
                                    categoryMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            RegistrationLabel("내용")
            RegistrationTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "내용을 입력하세요",
                minLines = 8,
                maxLines = 8
            )
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = {
                    val documentTitle = title.ifBlank { "제목 없는 자료" }
                    val documentCategory = selectedCategory.ifBlank { DefaultRegistrationCategory }
                    val hasAttachment = selectedImageUri != null || scannedImageUris.isNotEmpty()
                    val documentContent = contentForDocumentSave(hasAttachment, content)
                    onSave(
                        FieldDocument(
                            id = "local-${System.currentTimeMillis()}",
                            title = documentTitle,
                            category = documentCategory,
                            date = LocalDate.now().toString(),
                            source = if (hasAttachment) DocumentSource.IMAGE else DocumentSource.TEXT,
                            content = documentContent,
                            description = documentContent.take(80),
                            imageUri = selectedImageUri,
                            imageUris = scannedImageUris,
                            ocrStatus = ocrStatus,
                            thumbnailColor = if (hasAttachment) Color(0xFFE5F2FF) else Color(0xFFEAE4FA),
                            icon = if (hasAttachment) Icons.Default.Image else Icons.Default.Description
                        ),
                        ocrSearchText.takeIf { ocrStatus == OcrStatus.COMPLETED }
                    )
                },
                enabled = !isImageOptimizing && !isOcrExtracting && !isSaving && attachmentPreparationError == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("저장 후 공유", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF858E9C), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("저장 시 모든 기기에 자동 공유", color = Color(0xFF7E8795), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RegisterMethodCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: Color,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .height(128.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(31.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Ink.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RegistrationLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(" *", color = Color(0xFFE53935), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    readOnly: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    compact: Boolean = false
) {
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        focusedBorderColor = SamsungBlue,
        unfocusedBorderColor = Color(0xFFD9DEE7)
    )

    if (compact) {
        val interactionSource = remember { MutableInteractionSource() }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(45.dp),
            readOnly = readOnly,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = {
                        Text(placeholder, color = Color(0xFF9BA3B1), style = MaterialTheme.typography.bodySmall)
                    },
                    colors = colors,
                    contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = true,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                )
            }
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(placeholder, color = Color(0xFF9BA3B1), style = MaterialTheme.typography.bodySmall) },
            readOnly = readOnly,
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            shape = RoundedCornerShape(8.dp),
            colors = colors
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White,
        unfocusedContainerColor = Color.White,
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        focusedLeadingIconColor = SamsungBlue,
        unfocusedLeadingIconColor = Color(0xFF718096),
        focusedTrailingIconColor = SamsungBlue,
        unfocusedTrailingIconColor = Color(0xFF718096)
    )

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Start),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        text = "에러코드, 제품명, 키워드 검색",
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Start,
                        color = Color(0xFFB6BEC9)
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "검색") },
                trailingIcon = {
                    IconButton(onClick = {}) { Icon(Icons.Default.FilterList, contentDescription = "필터") }
                },
                colors = colors,
                contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            )
        }
    )
}

@Composable
private fun CategoryTabs(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val selected = category == selectedCategory
            Surface(
                modifier = Modifier.clickable { onCategorySelected(category) },
                shape = CircleShape,
                color = if (selected) SamsungBlue else Color.White,
                contentColor = if (selected) Color.White else Ink,
                border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.75f)),
                shadowElevation = if (selected) 0.dp else 2.dp
            ) {
                Text(
                    text = category,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: FieldDocument,
    loadImageThumbnail: Boolean = true,
    resolveThumbnailUrl: suspend (String) -> String? = { imagePath -> imagePath },
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(document, loadImageThumbnail, resolveThumbnailUrl)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TinyChip(document.category, SamsungBlueLight, SamsungBlue)
                    TinyChip(document.detail, Color(0xFFF1F3F6), Color(0xFF5E6878))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = document.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = 0.67f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(document.date, style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A94A6))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextDocumentEditScreen(
    document: FieldDocument,
    isSaving: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSave: (title: String, category: String, content: String) -> Unit
) {
    var title by rememberSaveable(document.id) { mutableStateOf(document.title) }
    var category by rememberSaveable(document.id) { mutableStateOf(document.category) }
    var content by rememberSaveable(document.id) { mutableStateOf(document.content) }
    Scaffold(
        containerColor = SoftGray,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("자료 내용 수정", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SamsungBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = { BottomBackNavigationBar(onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (document.source == DocumentSource.IMAGE) {
                    "첨부 이미지·스캔 원본, OCR 정보와 등록자는 수정할 수 없습니다."
                } else {
                    "등록자는 수정할 수 없습니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7E8795)
            )
            RegistrationLabel("제목")
            RegistrationTextField(title, { title = it }, "제목을 입력하세요", compact = true)
            RegistrationLabel("카테고리")
            RegistrationTextField(category, { category = it }, "카테고리를 입력하세요", compact = true)
            RegistrationLabel("내용")
            RegistrationTextField(content, { content = it }, "내용을 입력하세요", minLines = 10, maxLines = 10)
            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage, color = Color(0xFFB3261E), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onSave(title, category, content) },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SamsungBlue)
            ) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("수정 저장", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DocumentDetailScreen(
    document: FieldDocument,
    onBack: () -> Unit,
    onImageClick: (String) -> Unit,
    deleteError: String? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var showDeleteConfirmation by rememberSaveable(document.id) { mutableStateOf(false) }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("자료 삭제", fontWeight = FontWeight.Bold) },
            text = { Text("이 자료를 삭제할까요? 삭제한 자료와 첨부파일은 복구할 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete?.invoke()
                    }
                ) {
                    Text("삭제", color = Color(0xFFB3261E))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("취소") }
            }
        )
    }
    Scaffold(
        containerColor = SoftGray,
        topBar = {
            Column(
                modifier = Modifier.background(
                    Brush.verticalGradient(listOf(SamsungBlueDark, SamsungBlue))
                )
            ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (onEdit != null) {
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Default.Edit, contentDescription = "자료 수정", tint = SamsungBlue)
                            }
                        }
                        if (onDelete != null) {
                            IconButton(onClick = { showDeleteConfirmation = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "자료 삭제", tint = Color(0xFFB3261E))
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onBack, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 화면")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (!deleteError.isNullOrBlank()) {
                Text(
                    text = "$deleteError 삭제 버튼을 눌러 다시 시도할 수 있습니다.",
                    color = Color(0xFFB3261E),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(1.dp, BorderGray)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        TinyChip(document.category, SamsungBlueLight, SamsungBlue)
                        TinyChip(
                            if (document.source == DocumentSource.IMAGE) "이미지" else "직접 입력",
                            Color(0xFFF1F3F6),
                            Color(0xFF5E6878)
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "등록일  ${document.date}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF7E8795)
                        )
                        Text(
                            text = "등록자: ${document.createdByName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF7E8795)
                        )
                    }
                }
            }

            if (document.source == DocumentSource.IMAGE) {
                OriginalImagePreview(
                    imageUris = document.imageUris.ifEmpty { listOfNotNull(document.imageUri) },
                    onClick = onImageClick
                )
                imageContentForDetail(document.content)?.let { content ->
                    TextDocumentContent(content)
                }
            } else {
                TextDocumentContent(document.content)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TextDocumentContent(content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "내용",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Ink
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, BorderGray),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            LinkedDocumentText(
                content = content,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}

@Composable
private fun OriginalImagePreview(imageUris: List<String>, onClick: (String) -> Unit) {
    val pages = imageUris.ifEmpty { listOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "원본 이미지",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Ink
        )
        pages.forEachIndexed { index, imageUri ->
            if (imageUris.size > 1) {
                Text("페이지 ${index + 1}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF7E8795))
            }
            OriginalImagePage(imageUri.takeIf { it.isNotBlank() }, onClick)
        }
        Text(
            text = "이미지를 누르면 전체 화면에서 확대·축소할 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7E8795)
        )
    }
}

@Composable
private fun OriginalImagePage(imageUri: String?, onClick: (String) -> Unit) {
    var imageFailed by remember(imageUri) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = imageUri != null && !imageFailed, onClick = { imageUri?.let(onClick) }),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (imageUri != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                SubcomposeAsyncImage(
                    model = imageUri,
                    contentDescription = "원본 이미지 전체 화면 보기",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentScale = ContentScale.Fit,
                    loading = { ImageLoadingPlaceholder() },
                    error = { ImageUnavailablePlaceholder("원본 이미지를 불러올 수 없습니다.") },
                    onLoading = { imageFailed = false },
                    onSuccess = { imageFailed = false },
                    onError = { imageFailed = true }
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("전체 화면", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            ImageUnavailablePlaceholder("원본 이미지를 불러올 수 없습니다.")
        }
    }
}

@Composable
private fun RegistrationImagePreview(imageUri: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (imageUri != null) {
            SubcomposeAsyncImage(
                model = imageUri,
                contentDescription = "등록할 고화질 이미지 미리보기",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentScale = ContentScale.Fit,
                loading = { ImageLoadingPlaceholder() },
                error = { ImageUnavailablePlaceholder("이미지 미리보기를 불러올 수 없습니다.") }
            )
        } else {
            ImageUnavailablePlaceholder("이미지 미리보기를 불러올 수 없습니다.")
        }
    }
}

private const val IMAGE_VIEWER_PAN_SPEED_MULTIPLIER = 2f

@Composable
private fun FullScreenImageViewer(imageUri: String?, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previousRequestedOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        onDispose {
            if (activity != null && previousRequestedOrientation != null) {
                activity.requestedOrientation = previousRequestedOrientation
            }
        }
    }
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var rotationDegrees by remember(imageUri) { mutableStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val isQuarterTurn = ((rotationDegrees % 360f) + 360f) % 360f in setOf(90f, 270f)
    val imageFrameModifier = if (isQuarterTurn && viewportSize != IntSize.Zero) {
        with(LocalDensity.current) {
            Modifier.size(width = viewportSize.height.toDp(), height = viewportSize.width.toDp())
        }
    } else {
        Modifier.fillMaxSize()
    }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        if (newScale <= 1f) {
            offsetX = 0f
            offsetY = 0f
        } else {
            val maxOffsetX = viewportSize.width * (newScale - 1f) / 2f
            val maxOffsetY = viewportSize.height * (newScale - 1f) / 2f
            offsetX = (offsetX + panChange.x * IMAGE_VIEWER_PAN_SPEED_MULTIPLIER)
                .coerceIn(-maxOffsetX, maxOffsetX)
            offsetY = (offsetY + panChange.y * IMAGE_VIEWER_PAN_SPEED_MULTIPLIER)
                .coerceIn(-maxOffsetY, maxOffsetY)
        }
    }

    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (imageUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .transformable(transformableState)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2f
                            }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = imageUri,
                    contentDescription = "확대 가능한 원본 이미지",
                    contentScale = ContentScale.Fit,
                    modifier = imageFrameModifier
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                            rotationZ = rotationDegrees
                        ),
                    loading = { ImageLoadingPlaceholder() },
                    error = { ImageUnavailablePlaceholder("원본 이미지를 불러올 수 없습니다.") }
                )
            }
        } else {
            ImageUnavailablePlaceholder("원본 이미지를 불러올 수 없습니다.")
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 4.dp)
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(28.dp)),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                IconButton(
                    onClick = { rotationDegrees -= 90f },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateLeft,
                        contentDescription = "원본 이미지 90도 반시계 방향 회전",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(
                    onClick = { rotationDegrees += 90f },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = "원본 이미지 90도 시계 방향 회전",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "두 손가락으로 확대·축소 · 두 번 탭하여 확대",
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 12.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 화면", tint = Color.White)
        }
    }
}

@Composable
private fun ImageLoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = SamsungBlue, strokeWidth = 3.dp)
    }
}

@Composable
private fun ImageUnavailablePlaceholder(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF8A94A6), modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color(0xFF7E8795), textAlign = TextAlign.Center)
    }
}

private fun createCameraCacheFile(context: Context): File? = runCatching {
    File.createTempFile("fieldshare_camera_", ".jpg", context.cacheDir)
}.onFailure { error ->
    Log.e("FieldShareImage", "카메라 임시 파일 생성 실패: ${error.javaClass.simpleName}: ${error.message}", error)
}.getOrNull()

private fun optimizeImageUrisToFileProviderUris(
    context: Context,
    sourceUris: List<Uri>,
    cachePrefix: String,
    logTag: String
): List<Result<Uri>> = sourceUris.mapIndexed { index, sourceUri ->
    runCatching {
        val cachedFile = if (cachePrefix == "scan") {
            copyScannerPageToCache(context, sourceUri, index + 1)
        } else {
            copyImageUriToCache(context, sourceUri, cachePrefix, index + 1, logTag)
        }
        try {
            ImageOptimizer.optimize(context, cachedFile)
        } finally {
            if (cachedFile.exists() && !cachedFile.delete()) {
                Log.w(logTag, "이미지 ${index + 1}장 임시 파일을 정리하지 못했습니다.")
            }
        }
    }.onFailure { error ->
        Log.e(
            logTag,
            "이미지 ${index + 1}장 최적화 준비 실패: uri=$sourceUri, ${error.javaClass.simpleName}: ${error.message}",
            error
        )
    }
}

private fun copyScannerPageToCache(context: Context, scannerUri: Uri, pageNumber: Int): File =
    copyImageUriToCache(context, scannerUri, "scan", pageNumber, "FieldShareScan")

private fun copyImageUriToCache(
    context: Context,
    sourceUri: Uri,
    cachePrefix: String,
    imageNumber: Int,
    logTag: String
): File {
    val directory = File(context.cacheDir, "registration-images")
    if (!directory.exists() && !directory.mkdirs()) {
        val error = ImageOptimizationException("이미지 임시 폴더를 만들 수 없습니다.")
        Log.e(logTag, "이미지 ${imageNumber}장 캐시 폴더 생성 실패: ${error.javaClass.simpleName}: ${error.message}", error)
        throw error
    }
    val outputFile = File.createTempFile("fieldshare_${cachePrefix}_${imageNumber}_", ".jpg", directory)
    try {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw ImageOptimizationException("이미지 입력 스트림을 열 수 없습니다.")
        input.use { source ->
            FileOutputStream(outputFile).use { destination -> source.copyTo(destination) }
        }
    } catch (error: Throwable) {
        Log.e(
            logTag,
            "이미지 ${imageNumber}장 캐시 복사 실패: uri=$sourceUri, ${error.javaClass.simpleName}: ${error.message}",
            error
        )
        outputFile.delete()
        throw error
    }
    try {
        validateImageSourceFile(outputFile)
        return outputFile
    } catch (error: Throwable) {
        Log.e(logTag, "이미지 ${imageNumber}장 캐시 파일 검증 실패: ${error.javaClass.simpleName}: ${error.message}", error)
        outputFile.delete()
        throw error
    }
}

internal fun <T> optimizedValuesOrNull(optimizationResults: List<Result<T>>): List<T>? =
    optimizationResults.takeIf { results -> results.all { it.isSuccess } }?.map { it.getOrThrow() }

internal fun optimizedUrisOrNull(optimizationResults: List<Result<Uri>>): List<Uri>? =
    optimizedValuesOrNull(optimizationResults)

internal fun imagePreparationFailureMessage(failedIndexes: List<Int>): String =
    "이미지 ${failedIndexes.size}장을 2,000px로 처리하지 못했습니다. 다른 이미지를 선택하거나 다시 시도해 주세요."

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun Thumbnail(
    document: FieldDocument,
    loadImage: Boolean = true,
    resolveThumbnailUrl: suspend (String) -> String? = { imagePath -> imagePath }
) {
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(document.thumbnailColor),
        contentAlignment = Alignment.Center
    ) {
        // A caller can explicitly opt out of thumbnail loading without starting Storage work.
        val imagePath = document.imageUri.takeIf { loadImage }
        val imageUrl by produceState<String?>(
            initialValue = null,
            key1 = imagePath,
            key2 = resolveThumbnailUrl
        ) {
            if (imagePath != null) {
                value = resolveThumbnailUrl(imagePath)
            }
        }
        if (loadImage && imageUrl != null) {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = "${document.title} 썸네일",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { ThumbnailFallback(document) },
                error = { ThumbnailFallback(document) }
            )
        } else {
            ThumbnailFallback(document)
        }
    }
}

@Composable
private fun ThumbnailFallback(document: FieldDocument) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .shadow(3.dp, RoundedCornerShape(10.dp))
            .background(Color.White, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(document.icon, contentDescription = null, tint = SamsungBlue, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun TinyChip(text: String, background: Color, content: Color) {
    Surface(color = background, shape = CircleShape) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SyncStatusBar(syncState: FirebaseSyncUiState) {
    val title = when (syncState) {
        FirebaseSyncUiState.SYNCHRONIZED -> "실시간 동기화됨"
        FirebaseSyncUiState.CONNECTING -> "Firebase 연결 중"
        FirebaseSyncUiState.OFFLINE_CACHE -> "오프라인 캐시 사용 중"
        FirebaseSyncUiState.ERROR -> "동기화 오류"
    }
    val subtitle = when (syncState) {
        FirebaseSyncUiState.SYNCHRONIZED -> "Firestore 변경 사항을 수신 중"
        FirebaseSyncUiState.CONNECTING -> "문서 목록을 확인하고 있습니다"
        FirebaseSyncUiState.OFFLINE_CACHE -> "네트워크 복구 시 다시 동기화"
        FirebaseSyncUiState.ERROR -> "네트워크와 권한을 확인해 주세요"
    }
    val tint = if (syncState == FirebaseSyncUiState.SYNCHRONIZED) SuccessGreen else SamsungBlue
    Surface(color = Color.White, shadowElevation = 6.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(26.dp).background(Color(0xFFE5F6EA), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (syncState == FirebaseSyncUiState.CONNECTING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(title, color = tint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Color(0xFF7E8795), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, heightDp = 840)
@Composable
private fun FieldShareHomePreview() {
    FieldShareTheme { FieldShareHomeScreen() }
}

@Preview(showBackground = true, showSystemUi = true, heightDp = 840)
@Composable
private fun DocumentRegistrationPreview() {
    FieldShareTheme {
        DocumentRegistrationScreen(
            selectedCategory = DefaultRegistrationCategory,
            onCategoryChange = {},
            onBack = {},
            onSave = { _, _ -> }
        )
    }
}

@Preview(showBackground = true, showSystemUi = true, heightDp = 840)
@Composable
private fun SyncStatusPreview() {
    FieldShareTheme {
        SyncStatusScreen(
            onBack = {},
            syncState = FirebaseSyncUiState.SYNCHRONIZED,
            documentCount = 0L,
            activities = emptyList(),
            presence = PresenceSummary()
        )
    }
}
