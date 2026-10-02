package com.newoether.agora.assistant.live

/**
 * Pure transcript state for the live-voice call screen: finalized turn lines plus the two
 * in-flight caption lines (user / model) of the turn currently being spoken.
 *
 * The controller reports the turn's accumulated text after every server update and fires
 * [commit][LiveTranscriptLog.commitTurn] on turn boundaries; this class owns only the
 * view-shaping policy — no Android, no Room, no audio. Room remains the durable truth
 * (`development/system-assistant.md`); this log is display state only.
 *
 * Rules:
 * - A finalized turn collapses to at most one USER line and/or one MODEL line (empty sides are
 *   dropped, mirroring what gets persisted).
 * - The in-flight lines render even while empty: the caption slot shows who is "speaking".
 * - Barge-in resets the model side: the controller reports an empty model transcript after an
 *   interruption, and the log mirrors whatever it is told.
 *
 * Main-thread confined, NOT thread-safe: the controller fires its callbacks from OkHttp's
 * reader thread, so the call screen must funnel every [onTranscript] / [commitTurn] mutation
 * onto the main thread before touching this class (see VoiceModeActivity). Confinement beats
 * per-method synchronization here: it also serializes the recomposition version counter and
 * keeps the committed list's `toList()` snapshot consistent with mutation, with zero lock
 * overhead on the ~31 Hz caption cadence.
 */
internal class LiveTranscriptLog {
    private val committed = mutableListOf<Line>()
    private var pendingUser = ""
    private var pendingModel = ""

    enum class Side { USER, MODEL }
    data class Line(val side: Side, val text: String)

    /** Called with the in-flight turn's accumulated (user, model) text so far. */
    fun onTranscript(userSoFar: String, modelSoFar: String) {
        pendingUser = userSoFar.trim()
        pendingModel = modelSoFar.trim()
    }

    /** The in-flight turn settled: fold its non-empty sides into the committed history. */
    fun commitTurn() {
        if (pendingUser.isNotEmpty()) committed.add(Line(Side.USER, pendingUser))
        if (pendingModel.isNotEmpty()) committed.add(Line(Side.MODEL, pendingModel))
        pendingUser = ""
        pendingModel = ""
    }

    val isNotEmpty: Boolean get() = committed.isNotEmpty() || pendingUser.isNotEmpty() || pendingModel.isNotEmpty()

    val committedLines: List<Line> get() = committed.toList()

    val pendingUserLine: String get() = pendingUser
    val pendingModelLine: String get() = pendingModel
}
