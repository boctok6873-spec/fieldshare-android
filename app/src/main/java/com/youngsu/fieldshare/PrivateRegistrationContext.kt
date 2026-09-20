package com.youngsu.fieldshare

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import android.util.AtomicFile
import org.json.JSONObject

/** All drafts may become private via the destination selector, so none go to backed-up pictures. */
internal class PrivateRegistrationContext(base: Context, key: String, val draftId: String = UUID.randomUUID().toString()) : ContextWrapper(base) {
    init { require(key.matches(Regex("[a-zA-Z0-9_-]+"))); UUID.fromString(draftId) }
    internal val draft = File(base.cacheDir, "private-drive-drafts/$key/$draftId").apply { mkdirs(); setLastModified(System.currentTimeMillis()) }
    fun acquire() { synchronized(active) { active[draft.absolutePath] = (active[draft.absolutePath] ?: 0) + 1 } }
    fun release() { synchronized(active) { val n = (active[draft.absolutePath] ?: 1) - 1; if (n <= 0) active.remove(draft.absolutePath) else active[draft.absolutePath] = n } }
    fun beginSave() { File(draft, ".saving").writeText("pending") }
    fun endSave() { File(draft, ".saving").delete() }
    fun readSnapshot(): JSONObject? = runCatching { JSONObject(String(AtomicFile(File(draft, "draft.json")).readFully())) }.getOrNull()
    fun writeSnapshot(json: JSONObject) {
        if (!draft.exists()) return // An explicit success/cancel must not recreate its directory.
        val atomic = AtomicFile(File(draft, "draft.json")); val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
        draft.setLastModified(System.currentTimeMillis())
    }
    override fun getCacheDir(): File = draft
    override fun getFilesDir(): File = draft
    override fun getExternalFilesDir(type: String?): File = File(draft, type ?: "files").apply { mkdirs() }
    fun clearDraft() { if (!File(draft, ".saving").exists()) draft.deleteRecursively() }
    fun readable(uri: String): Boolean = runCatching {
        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.read() >= 0 } == true
    }.getOrDefault(false)

    companion object {
        private val active = mutableMapOf<String, Int>()
        fun clearInactiveAccount(base: Context, key: String) {
            require(key.matches(Regex("[a-zA-Z0-9_-]+")))
            synchronized(active) {
                File(base.cacheDir, "private-drive-drafts/$key").listFiles().orEmpty().forEach { candidate ->
                    if (candidate.isDirectory && candidate.absolutePath !in active && !File(candidate, ".saving").exists()) {
                        candidate.deleteRecursively()
                    }
                }
            }
        }
        /** Stale, inactive drafts only. A saved upload marker is deliberately never aged out. */
        fun cleanupStale(base: Context, now: Long = System.currentTimeMillis()) {
            val root = File(base.cacheDir, "private-drive-drafts")
            synchronized(active) {
                root.listFiles().orEmpty().filter { it.isDirectory }.forEach { account ->
                    account.listFiles().orEmpty().filter { it.isDirectory }.forEach { candidate ->
                        if (candidate.absolutePath !in active && !File(candidate, ".saving").exists() &&
                            now - candidate.lastModified() > 7 * 24 * 60 * 60_000L) candidate.deleteRecursively()
                    }
                }
            }
        }
    }
}
