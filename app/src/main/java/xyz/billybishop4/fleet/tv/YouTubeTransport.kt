package xyz.billybishop4.fleet.tv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal data class YouTubeHttpResponse(val status:Int,val text:String) { override fun toString()="YouTubeHttpResponse(status=$status)" }
internal interface YouTubeTransport {
    suspend fun request(url:String,body:String?=null,headers:Map<String,String> = emptyMap()):YouTubeHttpResponse
}
internal class YouTubeHttp : YouTubeTransport {
    override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse = suspendCancellableCoroutine { continuation ->
        val request=Request.Builder().url(url).header("User-Agent",USER_AGENT).apply {
            headers.forEach { (key,value)->header(key,value) }
            if(body!=null)post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val result=response.use { YouTubeHttpResponse(it.code,readBounded(it)) }
                    if(continuation.isActive)continuation.resume(result)
                } catch(e:Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        const val USER_AGENT="Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.3.1062949-gold (unlike Gecko) v8/8.8.278.8-jit gles Starboard/15"
        internal val client by lazy { OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
            .callTimeout(45,TimeUnit.SECONDS).connectionPool(okhttp3.ConnectionPool(5,60,TimeUnit.SECONDS)).build() }
        // Share DNS/TLS/HTTP2 connections with playback. Streaming calls have no total
        // duration deadline; read/connect timeouts still detect a broken connection.
        internal val mediaClient by lazy { client.newBuilder().readTimeout(20,TimeUnit.SECONDS).callTimeout(0,TimeUnit.SECONDS)
            .addNetworkInterceptor { chain->
                val request=chain.request()
                val safe=if(sameOrigin(chain.call().request().url.toString(),request.url.toString()))request else request.newBuilder()
                    .removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization").build()
                chain.proceed(safe)
            }.build() }
        internal fun readBounded(response:Response):String {
            val body=response.body ?: return ""
            if(body.contentLength()>8*1024*1024)throw IOException("YouTube response is too large")
            return body.byteStream().use { input ->
                val output=ByteArrayOutputStream();val bytes=ByteArray(16*1024)
                while(true) { val n=input.read(bytes);if(n<0)break
                    if(output.size()+n>8*1024*1024)throw IOException("YouTube response is too large")
                    output.write(bytes,0,n)
                }
                output.toString("UTF-8")
            }
        }
    }
}
