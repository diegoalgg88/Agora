package com.newoether.agora.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Propietario de una escritura nativa y un patch optimista fusionado por conversación.
 * Adaptado para Qwen Code ACP: usa [RemoteClient.updateSettings] → `set_config_option`
 * en lugar de FiloClient.
 *
 * El fork no tiene settingsKey/accessOwner/capabilities/optimisticSettings del upstream.
 * Se usa el owner computado de RemoteState ("$deviceId/${session.id}") como clave.
 */
internal class RemoteSettingsMutation(
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<RemoteState>,
    private val clientForState: (RemoteState) -> RemoteClient?,
    private val connectionForDevice: (String?) -> RemoteClient?,
    private val sameSource: (RemoteState, RemoteClient) -> Boolean,
    private val selectionEpoch: () -> Long,
    private val readEpoch: () -> Long,
    private val acceptPage: suspend (RemoteClient, String, Long, RemoteConversationPage) -> Unit,
    private val readsSettled: () -> Unit,
    private val report: (Exception) -> RemoteFailure?,
) {
    private data class Patch(val settings: RemoteSettings, val selection: Long)
    private class Owner(
        val id: String,
        val snapshot: RemoteState,
        val canonical: String,
        val access: String?,
        val device: String,
        val session: String,
        val client: RemoteClient,
        val connection: RemoteClient,
        var pending: Patch?,
        var active: Patch? = null,
        var confirmed: RemoteRuntime? = null,
    )
    private val owners = mutableMapOf<String, Owner>()
    private val pendingConfirmations = mutableMapOf<String, Pair<Owner, String>>()
    private var settingsVersion = 0L
    fun snapshotVersion(): Long = settingsVersion
    fun acceptsSnapshot(version: Long, ownerId: String): Boolean =
        version == settingsVersion && owners[ownerId]?.active == null
    fun confirmedRuntime(ownerId: String): RemoteRuntime? = owners[ownerId]?.takeIf {
        connectionForDevice(it.device) === it.connection
    }?.confirmed

    /** Asienta evidencia de cambio diferida a través del owner de failure notice existente. */
    fun observeRuntime(snapshot: RemoteState, runtime: RemoteRuntime?) {
        val key = ownerId(snapshot) ?: return
        val (owner, changeId) = pendingConfirmations[key] ?: return
        if (connectionForDevice(owner.device) !== owner.connection || !sameSource(owner.snapshot, owner.client)) {
            pendingConfirmations.remove(key)
            return
        }
        val change = runtime?.settingsChange ?: return
        if (change.id != changeId || change.state == "pending") return
        pendingConfirmations.remove(key)
        if (change.state == "failed" && isSelected(owner)) {
            report(QwenCodeProtocolException(message = "settings_unavailable"))
        }
    }

    fun hasConflict(snapshot: RemoteState): Boolean {
        val oid = ownerId(snapshot) ?: return false
        return owners.values.any {
            it.canonical == ownerCanonical(snapshot) && it.id != oid && (it.active != null || it.pending != null)
        }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (enabled == (snapshot.selectedEffort != null && snapshot.selectedEffort != "none")) return
        val effort = if (enabled) snapshot.settingsModel?.defaultReasoningEffort?.takeIf {
            it != "none" && it in snapshot.getEffortChoices()
        } ?: snapshot.getEffortChoices().firstOrNull { it != "none" } else "none"
        effort?.let(::setThinkingLevel)
    }

    fun setThinkingLevel(effort: String) {
        val snapshot = mutableState.value
        if (effort == snapshot.selectedEffort || effort !in snapshot.getEffortChoices()) return
        change(RemoteSettings(effort = effort))
    }

    fun setServiceTierEnabled(enabled: Boolean) {
        val snapshot = mutableState.value
        if (enabled == (snapshot.selectedServiceTier != null)) return
        val model = snapshot.settingsModel ?: return
        val tier = if (enabled) model.defaultServiceTier?.takeIf { value -> model.serviceTiers.orEmpty().any { it.id == value } }
            ?: model.serviceTiers?.firstOrNull()?.id ?: return else null
        setServiceTier(tier)
    }

    fun setServiceTier(tier: String?) {
        val snapshot = mutableState.value
        if (tier == snapshot.selectedServiceTier && snapshot.runtime?.serviceTierKnown == true) return
        change(RemoteSettings(serviceTier = tier, updateServiceTier = true))
    }

    private fun change(settings: RemoteSettings) {
        val snapshot = mutableState.value
        if (!snapshot.canEditSettings) return
        if (snapshot.isDraft) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(settings))
            return
        }
        val oid = ownerId(snapshot) ?: return
        val canonical = ownerCanonical(snapshot) ?: return
        if (hasConflict(snapshot)) return
        val device = snapshot.deviceId ?: return
        val session = snapshot.session ?: return
        val client = clientForState(snapshot) ?: return
        val connection = connectionForDevice(device) ?: return
        val existing = owners[oid]
        if (existing != null && existing.connection !== connection) return
        val patch = Patch(settings, selectionEpoch())
        if (existing != null) {
            existing.pending = patch.copy(settings = (existing.pending?.settings ?: RemoteSettings()).merge(settings))
            publish(existing)
            return
        }
        val owner = Owner(oid, snapshot, canonical, "none", device, session.id, client, connection,
            patch, confirmed = snapshot.runtime)
        owners[oid] = owner
        publish(owner)
        scope.launch { drain(owner) }
    }

    /** Los cambios de modelo retienen la barrera de control ordinaria en lugar de volverse optimistas. */
    suspend fun changeModel(client: RemoteClient, session: String, settings: RemoteSettings, snapshot: RemoteState) {
        val oid = ownerId(snapshot) ?: return
        val canonical = ownerCanonical(snapshot) ?: return
        if (hasConflict(snapshot)) return
        val device = snapshot.deviceId ?: return
        val connection = connectionForDevice(device) ?: return
        val selected = selectionEpoch()
        val generation = readEpoch()
        val owner = Owner(oid, snapshot, canonical, "none", device, session, client, connection, null)
        writeAndRead(owner, Patch(settings, selected), generation)
    }

    private suspend fun drain(owner: Owner) {
        var succeeded = false
        try {
            while (connectionForDevice(owner.device) === owner.connection && sameSource(owner.snapshot, owner.client)) {
                val patch = owner.pending ?: break
                owner.pending = null
                owner.active = patch
                publish(owner)
                try {
                    succeeded = false
                    val generation = readEpoch()
                    writeAndRead(owner, patch, generation)
                    succeeded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (isSelected(owner)) {
                        val failure = report(error)
                        mutableState.value = mutableState.value.copy(failure = failure)
                    }
                } finally {
                    owner.active = null
                    publish(owner)
                    if (isSelected(owner)) {
                        mutableState.value = mutableState.value.copy(
                            settingsRevision = mutableState.value.settingsRevision + 1,
                        )
                    }
                }
            }
        } finally {
            owner.active = null
            owner.pending = null
            publish(owner)
            owners.remove(owner.id)
            if (succeeded && isSelected(owner)) readsSettled()
        }
    }

    private suspend fun writeAndRead(owner: Owner, patch: Patch, generation: Long) {
        if (!sameSource(owner.snapshot, owner.client)) return
        settingsVersion++
        try {
            pendingConfirmations.remove(owner.id)
            owner.client.updateSettings(owner.session, patch.settings)
            if (!sameSource(owner.snapshot, owner.client)) return
            val page = owner.client.conversation(owner.session)
            if (!sameSource(owner.snapshot, owner.client)) return
            owner.confirmed = page.runtime
            if (isCurrent(owner, patch.selection)) acceptPage(owner.client, owner.session, generation, page)
            val change = page.runtime?.settingsChange
            if (owner.snapshot.settingsModel?.id == "claude" && change != null) {
                check((patch.settings.model == null || change.requested.model == patch.settings.model) &&
                    (patch.settings.effort == null || change.requested.effort == patch.settings.effort)) {
                    "Native settings evidence belongs to another change"
                }
                check(change.state != "failed") { "Native settings differ: model=${change.actualModel}, effort=${change.actualEffort}" }
                if (change.state == "pending") {
                    pendingConfirmations[owner.id] = owner to change.id
                    return
                }
            }
            pendingConfirmations.remove(owner.id)
            check(confirms(page.runtime, patch.settings)) { "Native settings were not confirmed" }
        } finally {
            settingsVersion++
        }
    }

    private fun isCurrent(owner: Owner, selection: Long): Boolean =
        selection == selectionEpoch() && isSelected(owner)

    private fun isSelected(owner: Owner): Boolean {
        val snapshot = mutableState.value
        return sameSource(owner.snapshot, owner.client) && ownerId(snapshot) == owner.id && "none" == owner.access && snapshot.deviceId == owner.device &&
            snapshot.session?.id == owner.session && connectionForDevice(owner.device) === owner.connection
    }

    private fun publish(owner: Owner) {
        val snapshot = mutableState.value
        val active = owner.active?.settings
        val pending = owner.pending?.settings
        val projection = if (connectionForDevice(owner.device) !== owner.connection) null else when {
            active != null && pending != null -> active.merge(pending)
            else -> pending ?: active
        }
        // El fork no tiene optimisticSettings; se actualiza draftSettings si es draft
        if (snapshot.isDraft && projection != null) {
            mutableState.value = snapshot.copy(draftSettings = snapshot.draftSettings.merge(projection))
        }
    }

    private fun confirms(runtime: RemoteRuntime?, settings: RemoteSettings): Boolean {
        runtime ?: return false
        return (settings.model == null || settings.model == runtime.model) &&
            (settings.effort == null || settings.effort == runtime.effort) &&
            (!settings.updateServiceTier ||
                (runtime.serviceTierKnown || runtime.serviceTier != null) &&
                settings.serviceTier.takeUnless { it == "default" } == runtime.serviceTier.takeUnless { it == "default" })
    }

    // Helpers para adaptar upstream → fork
    private fun ownerId(state: RemoteState): String? = state.owner
    private fun ownerCanonical(state: RemoteState): String? = state.owner
    private fun RemoteState.getEffortChoices(): List<String> =
        settingsModel?.reasoningEfforts.orEmpty()
}