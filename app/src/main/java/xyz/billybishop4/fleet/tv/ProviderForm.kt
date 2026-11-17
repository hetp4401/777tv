package xyz.billybishop4.fleet.tv

import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

internal class ProviderForm(private val a: MainActivity) : NativeScreen {
    private val m = a.model
    override val view = a.column()
    private val body = a.column().apply { pad(3) }
    private val scroll = ScrollView(a).apply { tag = "provider-form"; isFillViewport = true; addView(body) }
    private val advanced = a.column().apply { visibility = View.GONE }
    private var advancedBuilt = false
    private var closed = false
    private val toggle = a.action("Advanced settings") { showAdvanced(advanced.visibility != View.VISIBLE) }.quiet()
    private val status = a.text("", 13f, MUTED).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val save = a.action("Save", "Save profile", prominent = true) { submit(false) }.apply { id = View.generateViewId() }
    private val test = a.action("Test connection") { submit(true) }.quiet()
    private val inputs = linkedMapOf<String, EditText>()

    private fun field(label: String, get: (Provider) -> String, set: (Provider, String) -> Provider,
        password: Boolean = false, required: Boolean = false, target: LinearLayout = advanced, hint: String = "") {
        val p = m.providerDraft ?: return
        val group = a.field(label, get(p), password) { value ->
            m.providerDraft?.let { current -> if (get(current) != value) m.editProviderDraft(set(current, value)) }
        }
        if (required) (group.getChildAt(0) as TextView).text = "$label · Required"
        val input = group.findTag("field:$label") as EditText
        input.id = View.generateViewId(); input.hint = hint
        // Draft identities stay in the ViewModel, never in saved view-state parcels.
        input.isSaveEnabled = false; input.isSaveFromParentEnabled = false
        inputs[label] = input
        target.add(group)
    }
    private fun section(text: String) { advanced.add(a.text(text, 13f, MUTED, true)); advanced.gap(10) }
    init {
        if (android.os.Build.VERSION.SDK_INT >= 26) view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val title = if (m.route is Route.EditProvider) "Edit IPTV profile" else "Add IPTV profile"
        view.add(a.row().apply {
            addView(a.text(title, 24f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            button(a.action("Cancel", "Cancel editing") { m.discardProviderDraft() }.quiet())
            button(save)
        }); view.gap(8); view.add(status); view.gap(8)
        body.add(a.text("Connection", 13f, MUTED, true)); body.gap(10)
        field("Portal URL", { it.url }, { p, v -> p.copy(url = v) }, required = true, target = body, hint = "http://provider.example/stalker_portal/")
        inputs["Portal URL"]?.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; nextFocusUpId = save.id }
        field("MAC address", { it.mac }, { p, v -> p.copy(mac = v) }, required = true, target = body, hint = "00:1A:79:00:00:00")
        inputs["MAC address"]?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        field("Profile name (optional)", { it.name }, { p, v -> p.copy(name = v) }, target = body, hint = "A name for this profile")
        body.add(test, width = -2); body.gap(18)
        body.add(toggle, width = -2); body.gap(10); body.add(advanced)
        test.nextFocusRightId = save.id
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        updateToggle()
    }
    private fun buildAdvanced() {
        if (advancedBuilt) return
        advancedBuilt = true
        advanced.add(a.text("Optional provider values. Leave blank unless supplied.", 13f, MUTED)); advanced.gap(16)
        section("Device identity")
        field("Serial number (optional)", { it.serialNumber }, { p, v -> p.copy(serialNumber = v) })
        field("Device ID (optional)", { it.deviceId }, { p, v -> p.copy(deviceId = v) })
        field("Device ID 2 (optional)", { it.deviceId2 }, { p, v -> p.copy(deviceId2 = v) })
        field("Signature (optional)", { it.signature }, { p, v -> p.copy(signature = v) })
        advanced.gap(8); section("Device model & software")
        field("STB type", { it.stbType }, { p, v -> p.copy(stbType = v) })
        field("HW Version (optional)", { it.hwVersion }, { p, v -> p.copy(hwVersion = v) })
        field("HW Version 2 (optional)", { it.hwVersion2 }, { p, v -> p.copy(hwVersion2 = v) })
        field("PIN (optional)", { it.pin }, { p, v -> p.copy(pin = v) }, password = true)
        field("Version (optional)", { it.version }, { p, v -> p.copy(version = v) })
        field("Image Version (optional)", { it.imageVersion }, { p, v -> p.copy(imageVersion = v) })
        field("User-Agent (optional)", { it.userAgent }, { p, v -> p.copy(userAgent = v) })
        advanced.gap(8); section("Portal sign-in")
        field("Username (optional)", { it.username }, { p, v -> p.copy(username = v) })
        field("Password (optional)", { it.password }, { p, v -> p.copy(password = v) }, password = true)
        field("Timezone (optional)", { it.timezone }, { p, v -> p.copy(timezone = v) }, hint = "America/Los_Angeles")
        inputs["Timezone (optional)"]?.nextFocusDownId = save.id
    }
    private fun showAdvanced(visible: Boolean) {
        if (visible) buildAdvanced()
        advanced.visibility = if (visible) View.VISIBLE else View.GONE
        updateToggle()
    }
    private fun updateToggle() {
        val visible = advanced.visibility == View.VISIBLE
        toggle.text = if (visible) "Advanced settings  −" else "Advanced settings  +"
        toggle.contentDescription = if (visible) "Hide advanced settings" else "Advanced settings"
        toggle.tag = "button:${toggle.contentDescription}"
    }
    private fun submit(testing: Boolean) {
        val p = m.providerDraft ?: return
        if (testing) m.testProvider(p) else m.saveProvider(p)
        if (m.error != null && (testing || !m.providerStorageError)) focusInvalid(p)
        update()
    }
    private fun focusInvalid(p: Provider) {
        val newlineFields = listOf(
            "User-Agent (optional)" to p.userAgent, "Serial number (optional)" to p.serialNumber,
            "Device ID (optional)" to p.deviceId, "Device ID 2 (optional)" to p.deviceId2,
            "Signature (optional)" to p.signature, "STB type" to p.stbType,
            "Timezone (optional)" to p.timezone, "HW Version (optional)" to p.hwVersion,
            "HW Version 2 (optional)" to p.hwVersion2, "PIN (optional)" to p.pin,
            "Version (optional)" to p.version, "Image Version (optional)" to p.imageVersion)
        val label = when {
            runCatching { portalAddress(p.url) }.isFailure -> "Portal URL"
            !Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}", RegexOption.IGNORE_CASE).matches(p.mac.trim().replace('-', ':')) -> "MAC address"
            newlineFields.any { (_, v) -> v.any { it == '\r' || it == '\n' } } -> newlineFields.first { (_, v) -> v.any { it == '\r' || it == '\n' } }.first
            p.username.isBlank() != p.password.isBlank() -> if (p.username.isBlank()) "Username (optional)" else "Password (optional)"
            p.timezone.isNotBlank() && p.timezone.trim() !in java.util.TimeZone.getAvailableIDs() -> "Timezone (optional)"
            p.imageVersion.isNotBlank() && !Regex("[0-9]{1,10}").matches(p.imageVersion.trim()) -> "Image Version (optional)"
            !Regex("[A-Za-z0-9_-]{1,32}").matches(p.stbType.trim().ifBlank { "MAG250" }) -> "STB type"
            else -> return
        }
        if (label !in listOf("Portal URL", "MAC address", "Profile name (optional)")) showAdvanced(true)
        inputs[label]?.requestFocus()
    }
    override fun update() {
        val message = m.error ?: if (m.busy) "Testing connection…" else m.connectionStatus.orEmpty()
        status.text = message; status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        status.setTextColor(if (m.error != null) 0xFFFFC4C4.toInt() else MUTED)
        test.isEnabled = !m.busy; test.alpha = if (m.busy) .5f else 1f
    }
    override fun focusTarget(): View? = inputs["Portal URL"]
    override fun saveState(): Parcelable = Bundle().apply { putBoolean("advanced", advanced.visibility == View.VISIBLE); putInt("scroll", scroll.scrollY) }
    override fun restoreState(state: Parcelable?) {
        (state as? Bundle)?.let { saved ->
            showAdvanced(saved.getBoolean("advanced"))
            scroll.post { if (!closed) scroll.scrollTo(0, saved.getInt("scroll")) }
        }
    }
    override fun close() { closed = true }
}
