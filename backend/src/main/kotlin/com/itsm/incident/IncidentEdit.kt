package com.itsm.incident

/**
 * The fields of an incident that can be edited after it was reported. [json] is the name used in the API (the
 * request body of PATCH /api/incidents/{id}, `editableFields` of the detail and `changedFields` of the audit entry).
 * [affectsPriority] fields feed the priority calculation, so changing one recalculates priority and SLA deadlines.
 */
enum class EditField(val json: String, val affectsPriority: Boolean) {
    TITLE("title", false),
    DESCRIPTION("description", false),
    IMPACT("impact", true),
    URGENCY("urgency", true),
    AFFECTED_USERS("affectedUsers", true),
    SERVICE("serviceId", true),
}

/**
 * A partial change to an incident: null means "leave as it is". The service cannot be cleared with a null, so
 * [clearService] exists for that; it cannot be combined with a [serviceId].
 */
data class IncidentEdit(
    val title: String? = null,
    val description: String? = null,
    val impact: Impact? = null,
    val urgency: Urgency? = null,
    val affectedUsers: Int? = null,
    val serviceId: Long? = null,
    val clearService: Boolean = false,
) {
    /** The fields this edit asks to change (before it is known whether they differ from the current values). */
    fun requestedFields(): Set<EditField> = buildSet {
        if (title != null) add(EditField.TITLE)
        if (description != null) add(EditField.DESCRIPTION)
        if (impact != null) add(EditField.IMPACT)
        if (urgency != null) add(EditField.URGENCY)
        if (affectedUsers != null) add(EditField.AFFECTED_USERS)
        if (serviceId != null || clearService) add(EditField.SERVICE)
    }
}

/** What [Incident.edit] changed: only fields whose value really differed. */
data class EditResult(val changed: List<EditField>) {
    val isEmpty: Boolean get() = changed.isEmpty()
    val affectsPriority: Boolean get() = changed.any { it.affectsPriority }
    val names: List<String> get() = changed.map { it.json }
}
