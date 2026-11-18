package com.itsm.settings

import com.itsm.common.exception.domainRequire
import com.itsm.priority.PriorityService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Keys the admin API accepts, with their defaults. Add a line here to expose a new setting. */
object SettingKeys {
    const val PRIORITY_STRATEGY = "priority.strategy"
    const val ASSIGNMENT_STRATEGY = "assignment.strategy"

    val DEFAULTS: Map<String, String> = mapOf(
        PRIORITY_STRATEGY to "weighted",
        ASSIGNMENT_STRATEGY to "skill-least-loaded",
    )
}

data class SettingResponse(val key: String, val value: String)

data class UpdateSettingRequest(@field:NotBlank @field:Size(max = 500) val value: String)

/** Validates admin changes: only known keys; priority.strategy must be a registered strategy (P5 validates the assignment strategy). */
@Service
class SettingsAdminService(private val settings: SettingsService, private val priority: PriorityService) {

    fun list(): List<SettingResponse> =
        SettingKeys.DEFAULTS.map { (key, default) -> SettingResponse(key, settings.get(key, default)) }

    fun update(key: String, rawValue: String): SettingResponse {
        domainRequire(key in SettingKeys.DEFAULTS) { "Unknown setting '$key'. Known keys: ${SettingKeys.DEFAULTS.keys.sorted()}" }
        val value = rawValue.trim()
        domainRequire(value.isNotEmpty()) { "Value must not be blank" }
        if (key == SettingKeys.PRIORITY_STRATEGY) {
            domainRequire(value in priority.availableStrategies) {
                "Unknown priority strategy '$value'. Available: ${priority.availableStrategies.sorted()}"
            }
        }
        settings.set(key, value)
        return SettingResponse(key, value)
    }
}

@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
class AdminSettingsController(private val admin: SettingsAdminService) {

    @GetMapping
    fun list(): List<SettingResponse> = admin.list()

    @PutMapping("/{key}")
    fun update(@PathVariable key: String, @Valid @RequestBody request: UpdateSettingRequest): SettingResponse =
        admin.update(key, request.value)
}
