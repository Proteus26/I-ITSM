package com.itsm.service

import com.itsm.common.exception.ItsmException
import com.itsm.common.exception.NotFoundException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface ItServiceRepository : JpaRepository<ItService, Long> {
    fun findByActiveTrueOrderByNameAsc(): List<ItService>
    fun existsByCodeIgnoreCase(code: String): Boolean
    fun existsByNameIgnoreCase(name: String): Boolean
    fun existsByNameIgnoreCaseAndIdNot(name: String, id: Long): Boolean
}

class ServiceConflictException(message: String) : ItsmException(message, HttpStatus.CONFLICT)

/** Read access to the service catalog for other modules (P2 uses [criticalityOf] when it calculates a priority). */
@Service
class ServiceCatalog(private val repository: ItServiceRepository) {

    /** Criticality (1-4) of the service, or [DEFAULT_CRITICALITY] when [serviceId] is null or unknown. */
    @Transactional(readOnly = true)
    fun criticalityOf(serviceId: Long?): Int =
        serviceId?.let { repository.findById(it).orElse(null)?.criticality } ?: DEFAULT_CRITICALITY

    /** Active services ordered by name. */
    @Transactional(readOnly = true)
    fun listActive(): List<ItService> = repository.findByActiveTrueOrderByNameAsc()

    companion object {
        const val DEFAULT_CRITICALITY = 2
    }
}

/** Administrator operations on the catalog. Services are deactivated, never deleted, because incidents reference them. */
@Service
class ServiceAdminService(private val repository: ItServiceRepository) {

    @Transactional(readOnly = true)
    fun list(): List<ItService> = repository.findAll().sortedBy { it.name }

    @Transactional
    fun create(request: CreateServiceRequest): ItService {
        val service = ItService(request.code, request.name, request.description, request.criticality ?: 2, request.ownerTeam)
        if (repository.existsByCodeIgnoreCase(service.code)) throw ServiceConflictException("Service code '${service.code}' already exists")
        if (repository.existsByNameIgnoreCase(service.name)) throw ServiceConflictException("Service name '${service.name}' already exists")
        return saveOrConflict(service)
    }

    /**
     * Null fields in [request] leave the value unchanged; for [UpdateServiceRequest.description] and
     * [UpdateServiceRequest.ownerTeam] an empty string clears the value.
     */
    @Transactional
    fun update(id: Long, request: UpdateServiceRequest): ItService {
        val service = repository.findById(id).orElseThrow { NotFoundException("Service $id not found") }
        val name = request.name?.trim() ?: service.name
        if (repository.existsByNameIgnoreCaseAndIdNot(name, id)) throw ServiceConflictException("Service name '$name' already exists")
        service.update(
            name = name,
            description = request.description ?: service.description,
            criticality = request.criticality ?: service.criticality,
            ownerTeam = request.ownerTeam ?: service.ownerTeam,
            active = request.active ?: service.active,
        )
        return saveOrConflict(service)
    }

    /** The exists-checks above can race with a concurrent request; the unique constraints then decide and we answer 409, not 500. */
    private fun saveOrConflict(service: ItService): ItService =
        try {
            repository.saveAndFlush(service)
        } catch (e: DataIntegrityViolationException) {
            throw ServiceConflictException("Service code or name already exists")
        }
}
