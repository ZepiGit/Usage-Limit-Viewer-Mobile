package com.usagelimits.providers

import com.usagelimits.core.network.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.URLDecoder

class RecordedRequest(val request: Request, val body: String)

/** Answers requests from [replies] in order, recording each one; the last reply repeats. */
fun scriptedHttp(vararg replies: Pair<Int, String>, seen: MutableList<RecordedRequest> = mutableListOf()) =
    HttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                seen += RecordedRequest(request, body)
                val (code, json) = replies.getOrElse(seen.size - 1) { replies.last() }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("scripted")
                    .body(json.toResponseBody(HttpClient.JSON_MEDIA_TYPE))
                    .build()
            }
            .build(),
    )

fun formFields(body: String): Map<String, String> =
    body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
