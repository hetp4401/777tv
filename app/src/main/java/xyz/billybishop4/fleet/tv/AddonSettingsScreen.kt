package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.net.URI
import java.security.MessageDigest

/** Configured manifest URLs remain private; rows expose only add-on names and enabled state. */
internal class AddonSettingsScreen(private val a: MainActivity) : NativeScreen {
    private val m = a.model
    override val view = a.column().apply {
        tag = "addons-screen"; isSaveEnabled = false
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    private val rows = a.column()
    private val scroll = ScrollView(a).apply { tag = "addons-list"; isFillViewport = true; addView(rows) }
    private val status = a.text("", 13f, MUTED).apply {
        tag = "addons-status"; visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val storageWarning = a.text("Add-on storage could not be read. Reset add-ons to restore the defaults.", 13f, 0xFFFFC4C4.toInt()).apply {
        tag = "addons-storage-warning"; visibility = View.GONE
    }
    private val install = a.action("Install add-on", prominent = true) { submit() }
    private val reset = a.action("Reset add-ons") {
        if (!m.busy) dialog = a.confirm("Restore default add-ons? Custom add-ons will be removed.", "Reset add-ons") { m.resetAddons() }
    }.quiet()
    private lateinit var manifest: EditText
    private var dialog: AlertDialog? = null
    private var displayed: List<Addon>? = null
    private var localError: String? = null
    private var syncingDraft = false
    private var closed = false
    private var installing = false
    private var installInputGeneration = 0
    private var installedBefore = emptySet<String>()

    init {
        view.heading("Add-ons")
        val field = a.field("Manifest URL", m.addonDraft) { value ->
            if (!syncingDraft && value != m.addonDraft) {
                localError = null; m.setAddonDraft(value); m.addonEdited(); update()
            }
        }
        manifest = field.findTag("field:Manifest URL") as EditText
        manifest.apply {
            id = View.generateViewId(); isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "https://…/manifest.json"
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { submit(); true } else false
            }
        }
        view.add(a.row().apply {
            gravity = Gravity.BOTTOM
            addView(field, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = a.dp(12) })
            addView(install, LinearLayout.LayoutParams(-2, a.dp(46)).apply { bottomMargin = a.dp(10) })
        })
        view.add(status); view.gap(8)
        view.add(storageWarning); view.gap(4)
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.gap(8); view.add(reset, width = -2)
        update()
    }

    private fun submit() {
        if (closed || m.busy) return
        val url = manifest.text.toString().trim()
        localError = when {
            url.isBlank() -> "Enter a manifest URL."
            !validManifestUrl(url) -> "Enter an HTTP, HTTPS or Stremio manifest URL."
            else -> null
        }
        if (localError != null) { m.addonEdited(); update(); manifest.requestFocus(); return }
        hideKeyboard(); installedBefore = m.addons.mapTo(HashSet()) { it.manifestUrl }; installing = true
        installInputGeneration = a.focusInputGeneration
        m.installAddon(url); update()
    }

    private fun renderRows() {
        val previous = a.currentFocus?.tag?.toString()?.takeIf { it.startsWith("addon-options:") }
        val previousIndex = displayed?.indexOfFirst { addonOptionTag(it.manifestUrl) == previous } ?: -1
        val oldScroll = scroll.scrollY
        val inputGeneration = a.focusInputGeneration
        rows.removeAllViews()
        if (m.addons.isEmpty()) rows.add(a.text("No add-ons installed.", 14f, MUTED).apply { tag = "addons-empty"; pad(14) })
        m.addons.forEach { addon ->
            val name = displayName(addon)
            val row = a.row().apply {
                tag = addonOptionTag(addon.manifestUrl); id = View.generateViewId()
                contentDescription = "Options for $name"
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                minimumHeight = a.dp(64); setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
                background = a.focusBackground()
                addView(a.column().apply {
                    add(a.text(name, 15f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    gap(4); add(a.text(listOf(if (addon.enabled) "Enabled" else "Disabled",capabilityLabel(addon)).filter{it.isNotEmpty()}.joinToString(" · "),12f,MUTED))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(a.text("Options  ›", 13f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(16) })
                setOnClickListener { if (!m.busy) options(addon) }
            }
            rows.add(row); rows.gap(4)
        }
        displayed = m.addons.toList()
        scroll.post {
            if (!closed && a.focusInputGeneration == inputGeneration) {
                scroll.scrollTo(0, oldScroll)
                if (previous != null) {
                    val replacement = m.addons.getOrNull(previousIndex.coerceAtLeast(0)) ?: m.addons.lastOrNull()
                    (rows.findTag(previous) ?: replacement?.let { rows.findTag(addonOptionTag(it.manifestUrl)) } ?: manifest).requestFocus()
                }
            }
        }
    }

    private fun options(addon: Addon) {
        val name = displayName(addon)
        dialog = a.showActions(name, listOf(
            (if (addon.enabled) "Disable add-on" else "Enable add-on") to { m.setAddonEnabled(addon.manifestUrl, !addon.enabled) },
            "Refresh add-on" to { m.refreshAddon(addon.manifestUrl) },
            "Remove add-on" to {
                dialog = a.confirm("Remove $name?", "Remove add-on") { m.removeAddon(addon.manifestUrl) }
            },
        ))
    }

    override fun update() {
        if (closed) return
        if (displayed != m.addons) renderRows()
        if (manifest.text.toString() != m.addonDraft) {
            syncingDraft = true
            manifest.setText(m.addonDraft); manifest.setSelection(manifest.length())
            syncingDraft = false
        }
        install.isEnabled = !m.busy; reset.isEnabled = !m.busy
        for (i in 0 until rows.childCount) rows.getChildAt(i).isEnabled = !m.busy
        storageWarning.visibility = if (m.addonStorageError) View.VISIBLE else View.GONE
        val error = localError ?: m.error?.let(::safeMessage)
        val message = error ?: m.addonStatus?.let(::safeMessage) ?: if (m.busy) "Updating add-ons…" else ""
        status.text = message; status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        status.setTextColor(if (error != null) 0xFFFFC4C4.toInt() else MUTED)
        if (installing && !m.busy) {
            installing = false
            if (a.focusInputGeneration != installInputGeneration) return
            if (m.error != null || m.addonDraft.isNotEmpty()) manifest.requestFocus()
            else {
                val added = m.addons.firstOrNull { it.manifestUrl !in installedBefore }
                view.post { if (!closed && a.focusInputGeneration == installInputGeneration)
                    (added?.let { rows.findTag(addonOptionTag(it.manifestUrl)) } ?: install).requestFocus() }
            }
        }
    }

    override fun focusTarget(): View = manifest
    override fun saveState(): Parcelable = Bundle().apply { putInt("scroll", scroll.scrollY) }
    override fun restoreState(state: Parcelable?) {
        val y = (state as? Bundle)?.getInt("scroll") ?: return
        scroll.post { if (!closed) scroll.scrollTo(0, y) }
    }
    override fun close() { closed = true; dialog?.dismiss(); dialog = null }
    private fun hideKeyboard() = (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(manifest.windowToken, 0)

    private fun validManifestUrl(value: String) = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase(java.util.Locale.ROOT) in listOf("http", "https", "stremio") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
    private fun displayName(addon: Addon): String {
        val name = safeName(addon.name)
        val matches = m.addons.filter { safeName(it.name) == name }
        return if (matches.size > 1) "$name · Configuration ${matches.indexOfFirst { it.manifestUrl == addon.manifestUrl } + 1}" else name
    }
    private fun capabilityLabel(addon:Addon):String {
        val document=addon.document?:return ""
        val resources=document.optJSONArray("resources")?:return ""
        val names=(0 until resources.length()).mapNotNull { index -> when(val entry=resources.opt(index)) {
            is String -> entry
            is org.json.JSONObject -> entry.optString("name")
            else -> null
        } }.toSet()
        val catalogs=document.optJSONArray("catalogs")?.objects().orEmpty()
        val search=catalogs.any { catalog ->
            catalog.optJSONArray("extra")?.objects().orEmpty().any{it.optString("name")=="search"} ||
                catalog.optJSONArray("extraSupported")?.let{extras->(0 until extras.length()).any{extras.optString(it)=="search"}}==true
        }
        return listOfNotNull(if("catalog" in names && catalogs.isNotEmpty())"Catalogs" else null,
            if(search)"Search" else null,if("meta" in names)"Details" else null,if("stream" in names)"Streams" else null).joinToString(" · ")
    }
    private fun safeName(value: String): String = value.trim().replace(Regex("\\s+"), " ")
        .takeUnless { it.contains("://") || Regex("[?&][^\\s=]+=|(?:token|password|api[_-]?key)=", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        ?.take(80)?.ifBlank { "Add-on" } ?: "Add-on"
    private fun safeMessage(value: String): String = value
        .replace(Regex("(?:https?|stremio)://\\S+", RegexOption.IGNORE_CASE), "add-on")
        .replace(Regex("(?:[?&]|\\b)(?:token|password|passwd|secret|api[_-]?key|authorization)=[^\\s]+", RegexOption.IGNORE_CASE), "")
        .trim().ifBlank { "Could not update add-ons. Try again." }
}

/** Tags remain stable across list changes without exposing a configured manifest URL. */
internal fun addonOptionTag(manifestUrl: String): String = "addon-options:" + MessageDigest.getInstance("SHA-256")
    .digest(manifestUrl.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
