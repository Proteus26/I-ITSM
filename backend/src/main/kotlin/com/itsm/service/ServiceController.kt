package com.itsm.service

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

private const val NOT_BLANK = ".*\\S.*"

/** What any logged-in user sees in the catalog. */
data class ServiceSummary(val id: Long, val code: String, val name: String, val description: String?, val criticality: Int)

data class ServiceResponse(
    val id: Long, val code: String, val name: String, val description: String?,
    val criticality: Int, val ownerTeam: String?, val active: Boolean,
)

data class CreateServiceRequest(
    @field:NotBlank @field:Size(max = 50) val code: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 500) val description: String? = null,
    @field:Min(1) @field:Max(4) val criticality: Int? = null,
    @field:Size(max = 100) val ownerTeam: String? = null,
)

/** Every field is optional; null means "leave unchanged". The code cannot be changed. */
data class UpdateServiceRequest(
    @field:Pattern(regexp = NOT_BLANK, message = "must not be blank") @field:Size(max = 100) val name: String? = null,
    @field:Size(max = 500) val description: String? = null,
    @field:Min(1) @field:Max(4) val criticality: Int? = null,
    @field:Size(max = 100) val ownerTeam: String? = null,
    val active: Boolean? = null,
)

private fun ItService.toSummary() = ServiceSummary(requireNotNull(id), code, name, description, criticality)
private fun ItService.toResponse() = ServiceResponse(requireNotNull(id), code, name, description, criticality, ownerTeam, active)

@RestController
@RequestMapping("/api/services")
class ServiceController(private val catalog: ServiceCatalog) {

    /** Active services, for the "service" drop-down when reporting an incident. */
    @GetMapping
    fun list(): List<ServiceSummary> = catalog.listActive().map { it.toSummary() }
}

@RestController
@RequestMapping("/api/admin/services")
@PreAuthorize("hasRole('ADMIN')")
class AdminServiceController(private val admin: ServiceAdminService) {

    @GetMapping
    fun list(): List<ServiceResponse> = admin.list().map { it.toResponse() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateServiceRequest): ServiceResponse = admin.create(request).toResponse()

    @PatchMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateServiceRequest): ServiceResponse =
        admin.update(id, request).toResponse()
}
