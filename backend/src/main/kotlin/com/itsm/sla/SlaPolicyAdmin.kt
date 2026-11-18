package com.itsm.sla

import com.itsm.common.exception.ItsmException
import com.itsm.common.exception.NotFoundException
import com.itsm.common.exception.domainRequire
import com.itsm.incident.Priority
import com.itsm.service.ItServiceRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** [serviceId], [serviceCode] and [serviceName] are null for a default policy. */
data class SlaPolicyResponse(
    val id: Long, val serviceId: Long?, val serviceCode: String?, val serviceName: String?, val priority: Priority,
    val responseMinutes: Int, val resolutionMinutes: Int, val warningThresholdPct: Int, val isDefault: Boolean,
)

data class CreateSlaPolicyRequest(
    @field:NotNull val serviceId: Long?,
    @field:NotNull val priority: Priority?,
    @field:NotNull @field:Min(1) @field:Max(SlaPolicy.MAX_MINUTES.toLong()) val responseMinutes: Int?,
    @field:NotNull @field:Min(1) @field:Max(SlaPolicy.MAX_MINUTES.toLong()) val resolutionMinutes: Int?,
    @field:Min(1) @field:Max(99) val warningThresholdPct: Int? = null,
)

/** Every field is optional (null = unchanged). Service and priority of a policy never change. */
data class UpdateSlaPolicyRequest(
    @field:Min(1) @field:Max(SlaPolicy.MAX_MINUTES.toLong()) val responseMinutes: Int? = null,
    @field:Min(1) @field:Max(SlaPolicy.MAX_MINUTES.toLong()) val resolutionMinutes: Int? = null,
    @field:Min(1) @field:Max(99) val warningThresholdPct: Int? = null,
)

class SlaPolicyConflictException(message: String) : ItsmException(message, HttpStatus.CONFLICT)

/**
 * Administrator operations on `sla_policies`. The default policies (no service) can be edited but never deleted or
 * created, so every priority always has a fallback. Changes apply to deadlines computed afterwards (new incidents,
 * re-prioritisation); deadlines already stored on incidents are not recalculated.
 */
@Service
class SlaPolicyAdminService(
    private val policies: SlaPolicyRepository,
    private val services: ItServiceRepository,
) {
    /** Defaults first (P1 to P4), then service overrides by service name and priority. */
    @Transactional(readOnly = true)
    fun list(): List<SlaPolicyResponse> {
        val byId = services.findAll().associateBy { it.id }
        return policies.findAll()
            .map { p -> p.toResponse(p.serviceId?.let { byId[it] }?.let { it.code to it.name }) }
            .sortedWith(
                compareBy<SlaPolicyResponse> { it.serviceId != null }
                    .thenBy { it.serviceName?.lowercase() ?: "" }
                    .thenBy { it.priority },
            )
    }

    @Transactional
    fun create(request: CreateSlaPolicyRequest): SlaPolicyResponse {
        val serviceId = requireNotNull(request.serviceId)
        val priority = requireNotNull(request.priority)
        val service = services.findById(serviceId).orElseThrow { NotFoundException("Service $serviceId not found") }
        if (policies.existsByServiceIdAndPriority(serviceId, priority)) throw conflict(service.name, priority)
        val policy = SlaPolicy(
            serviceId, priority, requireNotNull(request.responseMinutes), requireNotNull(request.resolutionMinutes),
            request.warningThresholdPct ?: SlaPolicy.DEFAULT_WARNING_PCT,
        )
        return try {
            policies.saveAndFlush(policy).toResponse(service.code to service.name)
        } catch (e: DataIntegrityViolationException) {
            throw conflict(service.name, priority)
        }
    }

    @Transactional
    fun update(id: Long, request: UpdateSlaPolicyRequest): SlaPolicyResponse {
        val policy = policies.findById(id).orElseThrow { NotFoundException("SLA policy $id not found") }
        policy.update(
            request.responseMinutes ?: policy.responseMinutes,
            request.resolutionMinutes ?: policy.resolutionMinutes,
            request.warningThresholdPct ?: policy.warningThresholdPct,
        )
        policies.saveAndFlush(policy)
        val service = policy.serviceId?.let { services.findById(it).orElse(null) }
        return policy.toResponse(service?.let { it.code to it.name })
    }

    @Transactional
    fun delete(id: Long) {
        val policy = policies.findById(id).orElseThrow { NotFoundException("SLA policy $id not found") }
        domainRequire(!policy.isDefault) { "Default SLA policies cannot be deleted; edit their times instead" }
        policies.delete(policy)
    }

    private fun conflict(serviceName: String, priority: Priority) =
        SlaPolicyConflictException("Service '$serviceName' already has a $priority policy")

    private fun SlaPolicy.toResponse(service: Pair<String, String>?) = SlaPolicyResponse(
        requireNotNull(id), serviceId, service?.first, service?.second, priority,
        responseMinutes, resolutionMinutes, warningThresholdPct, isDefault,
    )
}

@RestController
@RequestMapping("/api/admin/sla-policies")
@PreAuthorize("hasRole('ADMIN')")
class AdminSlaPolicyController(private val admin: SlaPolicyAdminService) {

    @GetMapping
    fun list(): List<SlaPolicyResponse> = admin.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateSlaPolicyRequest): SlaPolicyResponse = admin.create(request)

    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateSlaPolicyRequest): SlaPolicyResponse = admin.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = admin.delete(id)
}
