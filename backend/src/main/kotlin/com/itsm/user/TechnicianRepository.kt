package com.itsm.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TechnicianRepository : JpaRepository<Technician, Long> {
    fun findByActiveTrueAndAvailableTrue(): List<Technician>

    @Query("select t from Technician t join t.skillCodes s where s = :code and t.available = true and t.active = true")
    fun findAvailableWithSkill(@Param("code") categoryCode: String): List<Technician>
}
