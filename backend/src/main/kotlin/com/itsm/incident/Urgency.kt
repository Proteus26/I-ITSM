package com.itsm.incident

/** How quickly the reporter needs a fix. [weight] (1-4) feeds the priority calculation. */
enum class Urgency(val weight: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4),
}
