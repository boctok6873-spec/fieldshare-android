package com.youngsu.fieldshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class DrivePage(val files: List<JSONObject>, val next: String?, val checkpoint: String? = null)

/** Only Google's API host receives tokens. No logging interceptor or backend dependency. */
internal interface PrivateDriveApi {
    suspend fun list(query: String, page: String? = null): DrivePage
    suspend fun startToken(): String
    suspend fun changes(page: String): DrivePage
    suspend fun read(id: String): ByteArray
    suspend fun generateId(): String
    suspend fun create(id: String, metadata: JSONObject, bytes: ByteArray? = null, mime: String = "application/json")
    suspend fun trash(id: String)
    suspend fun copy(sourceId: String, id: String, metadata: JSONObject)
}

internal class DriveApi(private val token: suspend () -> String) : PrivateDriveApi {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).retryOnConnectionFailure(false).build()

    private suspend fun request(path: String, params: Map<String, String> = emptyMap(),
        method: String = "GET", body: okhttp3.RequestBody? = null): ByteArray {
        val accessToken = token()
        return withContext(Dispatchers.IO) {
            val url = ("https://www.googleapis.com/" + path).toHttpUrl().newBuilder()
            params.forEach { (key, value) -> url.addQueryParameter(key, value) }
            http.newCall(Request.Builder().url(url.build()).header("Authorization", "Bearer $accessToken")
                .method(method, body).build()).execute().use { response ->
                val data = response.body?.bytes() ?: byteArrayOf()
                if (!response.isSuccessful) {
                    val reason = runCatching { JSONObject(String(data)).getJSONObject("error")
                        .getJSONArray("errors").getJSONObject(0).getString("reason") }.getOrDefault("")
                    throw DriveFailure(response.code, reason)
                }
                data
            }
        }
    }
    suspend fun userInfo(): JSONObject = JSONObject(String(request("oauth2/v3/userinfo")))
    override suspend fun list(query: String, page: String?): DrivePage {
        val params = mutableMapOf("q" to query, "spaces" to "drive", "pageSize" to "100",
            "fields" to "nextPageToken,files(id,trashed,appProperties,mimeType,version,thumbnailLink)")
        page?.let { params["pageToken"] = it }
        val json = JSONObject(String(request("drive/v3/files", params)))
        val a = json.getJSONArray("files")
        return DrivePage((0 until a.length()).map { a.getJSONObject(it) }, json.optString("nextPageToken").takeIf { it.isNotBlank() })
    }
    override suspend fun startToken() = JSONObject(String(request("drive/v3/changes/startPageToken"))).getString("startPageToken")
    override suspend fun changes(page: String): DrivePage {
        val json = JSONObject(String(request("drive/v3/changes", mapOf("pageToken" to page, "pageSize" to "100",
            "spaces" to "drive", "fields" to "nextPageToken,newStartPageToken,changes(fileId,removed,file(id,trashed,appProperties,mimeType,version,thumbnailLink))"))))
        val a = json.getJSONArray("changes")
        return DrivePage((0 until a.length()).map { a.getJSONObject(it) }, json.optString("nextPageToken").takeIf { it.isNotBlank() },
            json.optString("newStartPageToken").takeIf { it.isNotBlank() })
    }
    override suspend fun read(id: String) = request("drive/v3/files/$id", mapOf("alt" to "media"))
    override suspend fun generateId() = JSONObject(String(request("drive/v3/files/generateIds", mapOf("count" to "1"))))
        .getJSONArray("ids").getString(0)
    override suspend fun create(id: String, metadata: JSONObject, bytes: ByteArray?, mime: String) {
        metadata.put("id", id)
        val jsonBody = metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        try {
            if (bytes == null) request("drive/v3/files", method = "POST", body = jsonBody)
            else request("upload/drive/v3/files", mapOf("uploadType" to "multipart"), "POST",
                MultipartBody.Builder().setType("multipart/related".toMediaType()).addPart(jsonBody)
                    .addPart(bytes.toRequestBody(mime.toMediaType())).build())
        } catch (failure: DriveFailure) {
            // Pre-generated IDs make an ambiguous upload response safe to retry.
            if (failure.status != 409) throw failure
            ensureRetryTargetExists(id)
        }
    }
    override suspend fun trash(id: String) {
        try { request("drive/v3/files/$id", method = "PATCH",
            body = "{\"trashed\":true}".toRequestBody("application/json".toMediaType()))
        } catch (failure: DriveFailure) { if (failure.status != 404) throw failure }
    }
    override suspend fun copy(sourceId: String, id: String, metadata: JSONObject) {
        metadata.put("id", id)
        try { request("drive/v3/files/$sourceId/copy", method = "POST",
            body = metadata.toString().toRequestBody("application/json".toMediaType())) }
        catch (failure: DriveFailure) { if (failure.status != 409) throw failure; ensureRetryTargetExists(id) }
    }
    private suspend fun ensureRetryTargetExists(id: String) {
        val file = JSONObject(String(request("drive/v3/files/$id", mapOf("fields" to "id,trashed"))))
        if (file.optBoolean("trashed")) throw DriveFailure(404)
    }
}
