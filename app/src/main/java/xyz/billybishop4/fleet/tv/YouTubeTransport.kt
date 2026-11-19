package xyz.billybishop4.fleet.tv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal data class YouTubeHttpResponse(val status:Int,val text:String) { override fun toString()="YouTubeHttpResponse(status=$status)" }
internal interface YouTubeTransport {
    suspend fun request(url:String,body:String?=null,headers:Map<String,String> = emptyMap()):YouTubeHttpResponse
}
/** Browser-like visitor continuity for one native TV client. Never accept an
 * account cookie, share with media/tracking, or send it outside YouTube HTTPS. */
internal class YouTubeAnonymousCookies(private val now:()->Long=System::currentTimeMillis):CookieJar {
    private val values=linkedMapOf<String,Cookie>()
    private fun youtube(url:HttpUrl)=url.isHttps && (url.host=="youtube.com" || url.host=="www.youtube.com")
    @Synchronized override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) {
        if(!youtube(url))return
        values.entries.removeAll{it.value.expiresAt<=now()}
        for(cookie in cookies) {
            if(cookie.name !in allowed || cookie.domain!="youtube.com" && cookie.domain!="www.youtube.com" || cookie.toString().length>4096)continue
            val key="${cookie.name}/${cookie.domain}${cookie.path}"
            values.remove(key)
            if(cookie.expiresAt>now())values[key]=cookie
        }
        while(values.size>16 || values.values.sumOf{it.name.length+it.value.length+3}>8192)values.remove(values.keys.first())
    }
    @Synchronized override fun loadForRequest(url:HttpUrl):List<Cookie> {
        values.entries.removeAll{it.value.expiresAt<=now()}
        return if(youtube(url))values.values.filter{it.matches(url)} else emptyList()
    }
    companion object {
        private val allowed=setOf("YSC","VISITOR_INFO1_LIVE","VISITOR_PRIVACY_METADATA","PREF","SOCS","CONSENT")
    }
}
internal class YouTubeHttp(private val calls:Call.Factory=client,private val responseLimitBytes:Int=8*1024*1024) : YouTubeTransport {
    override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse = suspendCancellableCoroutine { continuation ->
        val request=Request.Builder().url(url).header("User-Agent",USER_AGENT).apply {
            headers.forEach { (key,value)->header(key,value) }
            if(body!=null)post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        val call=calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val result=response.use { YouTubeHttpResponse(it.code,readBounded(it,responseLimitBytes)) }
                    if(continuation.isActive)continuation.resume(result)
                } catch(e:Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        const val USER_AGENT="Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.3.1062949-gold (unlike Gecko) v8/8.8.278.8-jit gles Starboard/15"
        internal val client by lazy { OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
            .callTimeout(45,TimeUnit.SECONDS).connectionPool(okhttp3.ConnectionPool(5,60,TimeUnit.SECONDS)).build() }
        internal fun tvSession()=YouTubeHttp(client.newBuilder().cookieJar(YouTubeAnonymousCookies()).build())
        // Tracking requests contain account context: never follow a redirect, even
        // to another Google host. Keep the shared pool rather than a new socket pool.
        internal val trackingClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }
        // Suggestions are optional keystroke work. Reuse connections without
        // allowing an unresponsive endpoint to queue behind the next query.
        internal val suggestionClient by lazy { client.newBuilder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS)
            .callTimeout(4,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
        // Share DNS/TLS/HTTP2 connections with playback. Streaming calls have no total
        // duration deadline; read/connect timeouts still detect a broken connection.
        internal val mediaClient by lazy { client.newBuilder().readTimeout(20,TimeUnit.SECONDS).callTimeout(0,TimeUnit.SECONDS)
            .addNetworkInterceptor { chain->
                val request=chain.request()
                val safe=if(sameOrigin(chain.call().request().url.toString(),request.url.toString()))request else request.newBuilder()
                    .removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization").build()
                chain.proceed(safe)
            }.build() }
        internal fun readBounded(response:Response,maxBytes:Int=8*1024*1024):String {
            val body=response.body ?: return ""
            if(body.contentLength()>maxBytes)throw IOException("YouTube response is too large")
            return body.byteStream().use { input ->
                val output=ByteArrayOutputStream();val bytes=ByteArray(16*1024)
                while(true) { val n=input.read(bytes);if(n<0)break
                    if(output.size()+n>maxBytes)throw IOException("YouTube response is too large")
                    output.write(bytes,0,n)
                }
                output.toString("UTF-8")
            }
        }
    }
}
