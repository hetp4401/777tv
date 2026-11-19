package xyz.billybishop4.fleet.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts

/** Delegates speech recognition to the installed TV service; no microphone or recognizer is retained. */
internal class YouTubeVoice(
    private val a:MainActivity,
    private val launchForTest:((Intent)->Unit)?=null,
    private val availableForTest:(()->Boolean)?=null,
) {
    private data class Request(val route:Route,val feed:YouTubeFeed,val query:String,val inputGeneration:Int)
    private var pending:Request?=null
    private var inFlight=false
    private var closed=false
    private val launcher=if(launchForTest==null)a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        result(it.resultCode,it.data)
    } else null

    fun start() {
        if(closed || inFlight || a.model.section!=Section.YOUTUBE)return
        val intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT,"Search YouTube")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
        if(!(availableForTest?.invoke()?: (intent.resolveActivity(a.packageManager)!=null))) {
            a.showInfo("Voice search unavailable","This TV has no speech recognition app. Use the on-screen keyboard to search.")
            return
        }
        val m=a.model.youtube
        pending=Request(a.model.route,m.feed,m.query,a.focusInputGeneration);inFlight=true
        try { if(launchForTest!=null)launchForTest.invoke(intent) else launcher!!.launch(intent) }
        catch(_:ActivityNotFoundException){pending=null;inFlight=false;a.showInfo("Voice search unavailable","Use the on-screen keyboard to search.")}
        catch(_:SecurityException){pending=null;inFlight=false;a.showInfo("Voice search unavailable","The TV speech service could not open. Use the on-screen keyboard to search.")}
    }
    fun navigationChanged(route:Route) { if(pending?.route?.let{it!=route}==true)pending=null }
    internal fun result(code:Int,data:Intent?) {
        inFlight=false
        val request=pending?:return
        pending=null
        if(closed || code!=Activity.RESULT_OK || a.model.section!=Section.YOUTUBE || a.model.route!=request.route)return
        val m=a.model.youtube
        // A result from an older search must not replace text or a page chosen in the meantime.
        if(m.feed!=request.feed || m.query!=request.query || a.focusInputGeneration!=request.inputGeneration)return
        val query=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.take(200).orEmpty()
        if(query.length<2)return
        if(a.model.route is Route.Player)a.model.back()
        m.editQuery(query);m.submitSearch()
    }
    fun close(){closed=true;pending=null;inFlight=false;launcher?.unregister()}
}
