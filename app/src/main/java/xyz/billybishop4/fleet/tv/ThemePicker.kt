package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Focus previews a swatch. Only an explicit selection changes the saved app theme. */
internal fun MainActivity.showThemePicker(section:Section?=null) {
    val anchor=currentFocus
    val selectedId=when(section) {
        Section.SEARCH->model.preferences.onDemandThemeId
        Section.YOUTUBE->model.preferences.youtubeThemeId
        Section.LIVE->model.preferences.liveThemeId
        null->model.preferences.themeId
    }
    val current=ThemeCatalog.resolve(if(section==null)model.preferences.themeId else model.preferences.themeFor(section))
    val recommendedId=when(section) {
        Section.SEARCH->"cinema"
        Section.YOUTUBE->"tube"
        Section.LIVE->"aurora"
        null->null
    }
    // Rank the relevant preset first; stable IDs still own selection and persistence.
    val palettes=if(recommendedId==null)ThemeCatalog.all else ThemeCatalog.all.sortedBy { it.id!=recommendedId }
    fun select(id:String) {
        val prefs=model.preferences
        val next=when(section) {
            Section.SEARCH->prefs.copy(onDemandThemeId=id)
            Section.YOUTUBE->prefs.copy(youtubeThemeId=id)
            Section.LIVE->prefs.copy(liveThemeId=id)
            null->prefs.copy(themeId=id)
        }
        if(next!=prefs)model.updatePreferences(next)
    }
    val grid=GridLayout(this).apply { columnCount=2;tag="theme-picker";setPadding(dp(12),dp(4),dp(12),dp(8)) }
    val cards=palettes.mapIndexed { index,palette ->
        val isCurrent=palette.id==selectedId
        val isRecommended=palette.id==recommendedId
        column().apply {
            id=View.generateViewId();tag="theme-choice:${palette.id}"
            isFocusable=true;isFocusableInTouchMode=true;isClickable=true;isSelected=isCurrent
            if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
            if(Build.VERSION.SDK_INT>=30)stateDescription=if(isSelected)"Current theme" else "Not selected"
            contentDescription=palette.name+", "+palette.pairing+(if(isRecommended)", recommended" else "")+(if(isCurrent)", current theme" else "")
            setPadding(dp(12),dp(9),dp(12),dp(9))
            val colors=if(model.preferences.themeGradient && palette.prefersGradient)intArrayOf(palette.gradientStart,palette.gradientEnd) else intArrayOf(palette.background,palette.background)
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,colors).apply { cornerRadius=dp(14).toFloat() }
            foreground=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),round(Color.TRANSPARENT,FOCUS,14))
                addState(intArrayOf(),round(Color.TRANSPARENT,radius=14))
            }
            addView(row().apply {
                addView(text(palette.name,15f,palette.text,true),LinearLayout.LayoutParams(0,-2,1f))
                addView(text(if(isCurrent)"✓" else "●",14f,palette.accent).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            },LinearLayout.LayoutParams(-1,-2))
            val detail=if(isRecommended)"Recommended · "+palette.pairing else palette.pairing
            gap(4);add(text(detail,11f,palette.muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            grid.addView(this,GridLayout.LayoutParams(GridLayout.spec(index/2),GridLayout.spec(index%2,1f)).apply {
                width=0;height=dp(64);setGravity(Gravity.FILL_HORIZONTAL)
                setMargins(if(index%2==0)0 else dp(4),dp(4),if(index%2==0)dp(4) else 0,dp(4))
            })
        }
    }
    val scroll=ScrollView(this).apply { isFillViewport=false;addView(grid) }
    val title=section?.let { "${it.label} theme" }?:"App theme"
    val builder=AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton("Cancel",null)
    if(section!=null)builder.setNeutralButton("Use app theme",null)
    val dialog=builder.create()
    ownDialog(dialog)
    dialog.setOnDismissListener {
        releaseDialog(dialog)
        val generation=focusInputGeneration
        anchor?.postOnAnimation {
            if(generation==focusInputGeneration && anchor.isAttachedToWindow && anchor.isShown && anchor.hasWindowFocus())anchor.requestFocus()
        }
    }
    cards.forEachIndexed { index,card ->
        card.nextFocusLeftId=cards[if(index%2==0)index else index-1].id
        card.nextFocusRightId=cards[if(index%2==1 || index==cards.lastIndex)index else index+1].id
        card.nextFocusUpId=cards[(index-2).coerceAtLeast(index%2)].id
        card.setOnClickListener {
            // Dismiss first so rebuilding the Appearance page captures its originating row.
            dialog.dismiss()
            val id=palettes[index].id
            select(id)
        }
    }
    dialog.setOnShowListener {
        styleDialog(dialog)
        dialog.window?.setLayout(dp(620).coerceAtMost(resources.displayMetrics.widthPixels-dp(48)),ViewGroup.LayoutParams.WRAP_CONTENT)
        scroll.layoutParams=scroll.layoutParams.apply { height=minOf(dp(360),resources.displayMetrics.heightPixels-dp(172)) }
        val cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val inherited=if(section==null)null else dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
            isSelected=selectedId.isBlank()
            text=if(isSelected)"✓ Use app theme" else "Use app theme"
            contentDescription="Use app theme, "+ThemeCatalog.resolve(model.preferences.themeId).name
            if(Build.VERSION.SDK_INT>=30)stateDescription=if(isSelected)"Current setting" else "Not selected"
            setOnClickListener { dialog.dismiss();select("") }
        }
        val focusId=if(selectedId.isBlank() && recommendedId!=null)recommendedId else current.id
        val focus=cards[palettes.indexOfFirst { it.id==focusId }.coerceAtLeast(0)]
        cards.forEachIndexed { index,card ->card.nextFocusDownId=cards.getOrNull(index+2)?.id?:inherited?.id?:cancel.id }
        cancel.nextFocusUpId=focus.id;inherited?.nextFocusUpId=focus.id
        focus.requestFocusFromTouch()
    }
    dialog.show()
}
