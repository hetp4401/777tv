package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The same device-code sign-in sheet works from browsing and a playing video's actions. */
internal class YouTubeSignIn(private val a:MainActivity) {
    private val m=a.model.youtube
    private var dialog:AlertDialog?=null
    private var update:Job?=null
    private var qrJob:Job?=null
    val visible get()=dialog?.isShowing==true
    private val lifecycle=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP || event==Lifecycle.Event.ON_DESTROY)close() }
    fun show(forceSignIn:Boolean=false,signedIn:()->Unit={}) {
        if(visible)return
        if(m.account!=null && !forceSignIn){signedIn();return}
        val previousAccount=m.account
        val anchor=a.currentFocus
        val image=ImageView(a).apply { contentDescription="Scan to sign in to YouTube";scaleType=ImageView.ScaleType.FIT_CENTER }
        val code=a.text("",28f,bold=true).apply { tag="youtube-device-code";isSaveEnabled=false;letterSpacing=.08f }
        val message=a.text("Getting a sign-in code…",14f,MUTED).apply { tag="youtube-signin-status";maxLines=6 }
        val body=a.row().apply {
            gravity=Gravity.CENTER_VERTICAL;pad(18);add(image,width=a.dp(180),height=a.dp(180))
            addView(a.column().apply { add(code);gap(14);add(message) },LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.dp(20)})
        }
        var shown:YouTubeDeviceCode?=null
        var success=false
        val sheet=AlertDialog.Builder(a).setTitle("Sign in with Google").setView(body)
            .setNegativeButton("Cancel",null).setPositiveButton("New code",null).create()
        dialog=sheet;a.ownDialog(sheet);a.lifecycle.addObserver(lifecycle)
        sheet.setOnShowListener {
            a.styleDialog(sheet)
            sheet.window?.apply { setBackgroundDrawable(a.round(PANEL,radius=16));setLayout(a.dp(540).coerceAtMost(a.resources.displayMetrics.widthPixels-a.dp(48)),-2) }
            sheet.getButton(AlertDialog.BUTTON_NEGATIVE).quiet().apply { isAllCaps=false;requestFocusFromTouch() }
            sheet.getButton(AlertDialog.BUTTON_POSITIVE).quiet().apply { isAllCaps=false;setOnClickListener { m.startSignIn() } }
            m.startSignIn()
            // Poll only while this small modal is visible; playback's normal path has no auth ticker.
            update=a.lifecycleScope.launch {
                while(dialog===sheet) {
                    if(m.account!=null && !m.signInBusy && m.signInCode==null && m.signInError==null &&
                        (!forceSignIn || m.account!==previousAccount)){success=true;sheet.dismiss();break}
                    val value=m.signInCode
                    code.textIfChanged(value?.userCode.orEmpty());code.textSize=if(value!=null && value.userCode.length>12)16f else 28f
                    message.textIfChanged(m.signInError?:if(value==null)"Getting a sign-in code…" else
                        "Scan the QR code, or open ${value.verificationUrl.removePrefix("https://")} on your phone and enter this code.\n\nWaiting for Google…")
                    sheet.getButton(AlertDialog.BUTTON_POSITIVE).visibility=if(m.signInError!=null)android.view.View.VISIBLE else android.view.View.GONE
                    if(shown!=value) {
                        shown=value;qrJob?.cancel();image.setImageDrawable(null)
                        if(value!=null)qrJob=a.lifecycleScope.launch {
                            val bitmap=withContext(Dispatchers.Default) {
                                val matrix=QRCodeWriter().encode(value.verificationUrl,BarcodeFormat.QR_CODE,220,220)
                                val pixels=IntArray(matrix.width*matrix.height){i->if(matrix[i%matrix.width,i/matrix.width])Color.BLACK else Color.WHITE}
                                Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.RGB_565)
                            }
                            if(dialog===sheet && shown==value)image.setImageBitmap(bitmap)
                        }
                    }
                    delay(250)
                }
            }
        }
        sheet.setOnDismissListener {
            if(dialog===sheet)dialog=null
            a.releaseDialog(sheet);a.lifecycle.removeObserver(lifecycle)
            update?.cancel();update=null;qrJob?.cancel();qrJob=null;image.setImageDrawable(null);m.cancelSignIn()
            if(success)signedIn()
            val generation=a.focusInputGeneration
            anchor?.postOnAnimation { if(generation==a.focusInputGeneration && anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus())anchor.requestFocus() }
        }
        sheet.show()
    }
    fun close(){dialog?.dismiss()}
}
