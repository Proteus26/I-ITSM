package com.itsm.incident

/** How widely an incident is felt. [weight] (1-4) feeds the priority calculation. */
enum class Impact(val weight: Int) {
    INDIVIDUAL(1),
    TEAM(2),
    DEPARTMENT(3),
    ORGANISATION(4),
}
