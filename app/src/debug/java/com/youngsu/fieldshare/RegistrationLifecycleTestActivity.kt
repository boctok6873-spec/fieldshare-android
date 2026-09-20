package com.youngsu.fieldshare

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

@Suppress("DEPRECATION")
private fun ComponentActivity.showTestWindow() {
    if (android.os.Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
}

class PrivateUiTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTestWindow()
    }
}

/** Debug-only isolated Activity: no app authentication, network, or repository startup. */
class RegistrationLifecycleTestActivity : ComponentActivity() {
    lateinit var draftId: String
    var savedOcr: String? = null
    var savedImagesReadable = false
    var saveSucceeded = false
    var privateMode by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTestWindow()
        draftId = savedInstanceState?.getString("draft") ?: UUID.randomUUID().toString()
        privateMode = savedInstanceState?.getBoolean("private") ?: false
        val draft = PrivateRegistrationContext(this, "unconnected", draftId)
        if (savedInstanceState == null) {
            val image = File(draft.cacheDir, "image.png")
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val uri = FileProvider.getUriForFile(applicationContext, "$packageName.fileprovider", image).toString()
            val camera = File(draft.cacheDir, "waiting-camera.jpg").apply { writeText("waiting") }
            draft.writeSnapshot(JSONObject().put("title", "생명주기 회귀 테스트").put("content", "본문")
                .put("image", uri).put("scans", JSONArray(listOf(uri, uri))).put("camera", camera.absolutePath)
                .put("ocrStatus", "COMPLETED").put("ocr", "냉장고 RF85 모델 검색 텍스트"))
        }
        setContent {
            MaterialTheme {
                if (finished) Text("등록 종료")
                else DocumentRegistrationScreen(existingDraftId = draftId, isPrivate = privateMode,
                    onStorageChange = { privateMode = it }, selectedCategory = "냉장고", onCategoryChange = {},
                    onBack = { finished = true }, saveError = error,
                    onSave = { document, ocr, complete ->
                        savedOcr = ocr
                        savedImagesReadable = document.imageUris.all { draft.readable(it) }
                        complete(saveSucceeded)
                        if (saveSucceeded) finished = true else error = "테스트 저장 실패 · 재시도 가능"
                    })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", draftId); outState.putBoolean("private", privateMode)
        super.onSaveInstanceState(outState)
    }
}
