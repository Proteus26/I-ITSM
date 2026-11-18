package com.itsm.incident

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

// STUB: owned by P2. The real one must also extend JpaSpecificationExecutor<Incident> so search works.
interface IncidentRepository : JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident>
