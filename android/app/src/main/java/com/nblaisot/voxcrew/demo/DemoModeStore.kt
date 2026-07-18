package com.nblaisot.voxcrew.demo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nblaisot.voxcrew.roster.CrewMember

/**
 * Persisted Play Store / walkthrough demo mode. Fixture peers and BT names are
 * UI-only — never synced into [com.nblaisot.voxcrew.lanlink.LanIntercomEngine].
 */
class DemoModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _demoMembers = MutableStateFlow(
        if (_enabled.value) DemoFixtures.seededMembers() else emptyList(),
    )
    val demoMembers: StateFlow<List<CrewMember>> = _demoMembers.asStateFlow()

    /** @return true if demo mode is now enabled */
    fun toggle(): Boolean {
        val next = !_enabled.value
        setEnabled(next)
        return next
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        _demoMembers.value = if (enabled) DemoFixtures.seededMembers() else emptyList()
    }

    /** Preferred demo audio route key (earbuds) when demo is on. */
    fun preferredAudioRouteKey(): String? =
        if (_enabled.value) DemoFixtures.audioRouteKey(DemoFixtures.EARBUDS_ID) else null

    fun toggleRecipient(uid: String) {
        if (!_enabled.value) return
        _demoMembers.value = DemoRosterPolicy.afterToggle(_demoMembers.value, uid)
    }

    fun soloRecipient(uid: String) {
        if (!_enabled.value) return
        _demoMembers.value = DemoRosterPolicy.afterSolo(_demoMembers.value, uid)
    }

    fun forgetMember(uid: String) {
        if (!_enabled.value) return
        _demoMembers.value = DemoRosterPolicy.afterForget(_demoMembers.value, uid)
    }

    private companion object {
        const val PREFS = "voxcrew_demo_mode"
        const val KEY_ENABLED = "enabled"
    }
}
