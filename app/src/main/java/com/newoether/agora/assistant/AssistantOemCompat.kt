package com.newoether.agora.assistant

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.newoether.agora.util.DebugLog

/**
 * Some OEM skins cannot cleanly coexist with a declared [android.service.voice.VoiceInteractionService]
 * alongside a plain `ACTION_ASSIST` activity for the same app.
 *
 * Confirmed on-device, Samsung One UI, 2026-09-17: declaring both [AgoraVoiceInteractionService]
 * and [AssistantActivity] at the same time broke the side-button "Digital assistant" picker's
 * ability to resolve a friendly label AND to actually launch the app at all. Disabling the
 * VoiceInteractionService pair (keeping [AssistantActivity]'s plain `ACTION_ASSIST` path as the
 * sole mechanism) resolved both symptoms in the same test session. This matches Kai \u2014 a
 * separate, ACTION_ASSIST-only app \u2014 which has never exhibited this problem on the same device.
 *
 * `RoleManager.ROLE_ASSISTANT` only ever requires "at least one of" a VoiceInteractionService or
 * an ACTION_ASSIST activity (see the role's own documentation); running both simultaneously is
 * Agora's own choice for Gemini-parity on stock Android/Pixel (a real overlay session, screen
 * context capture), not something the platform requires. So it is safe to narrow to the single
 * mechanism confirmed to work on the affected OEM instead of running both everywhere.
 *
 * Scope is intentionally narrow: `Build.MANUFACTURER == "samsung"` only, not a general "OEM"
 * category. If another manufacturer is later found to have the same conflict, add it here with
 * its own dated evidence rather than broadening this check speculatively.
 */
internal object AssistantOemCompat {

    private const val TAG = "AssistantOemCompat"

    /**
     * Idempotent; cheap to call on every process start (a no-op after the first successful
     * disable on an affected device, and a no-op on every non-Samsung device).
     */
    fun applyOnFirstRun(context: Context) {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return
        val packageManager = context.packageManager
        disableIfEnabled(packageManager, context, AgoraVoiceInteractionService::class.java)
        disableIfEnabled(packageManager, context, AgoraVoiceInteractionSessionService::class.java)
    }

    private fun disableIfEnabled(packageManager: PackageManager, context: Context, clazz: Class<*>) {
        val component = ComponentName(context, clazz)
        val current = packageManager.getComponentEnabledSetting(component)
        if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return
        packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        DebugLog.d(TAG, "Disabled ${clazz.simpleName} on Samsung (One UI side-button conflict)")
    }
}
