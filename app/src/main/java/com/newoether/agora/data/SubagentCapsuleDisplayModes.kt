package com.newoether.agora.data

/** Presentation only; does not change delegation or child conversation ownership. */
object SubagentCapsuleDisplayModes {
    const val INDIVIDUAL = "individual"
    const val GROUPED = "grouped"
    const val DEFAULT = INDIVIDUAL

    fun normalize(value: String?): String = if (value == GROUPED) GROUPED else INDIVIDUAL
}
