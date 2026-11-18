package com.itsm.settings

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** One row of the `system_settings` key/value table (runtime configuration an administrator can change). */
@Entity
@Table(name = "system_settings")
class SystemSetting(
    @Id
    @Column(name = "key", nullable = false, updatable = false)
    val key: String,

    @Column(name = "value", nullable = false)
    var value: String,
)

interface SystemSettingRepository : JpaRepository<SystemSetting, String>

/** Reads and writes [SystemSetting]s. Knows nothing about which keys are valid; see [SettingsAdminService]. */
@Service
class SettingsService(private val repository: SystemSettingRepository) {

    /** The stored value of [key], or [default] when it has not been set. */
    @Transactional(readOnly = true)
    fun get(key: String, default: String): String = repository.findById(key).map { it.value }.orElse(default)

    /** Creates or replaces the value of [key]. */
    @Transactional
    fun set(key: String, value: String) {
        val existing = repository.findById(key).orElse(null)
        if (existing != null) existing.value = value else repository.save(SystemSetting(key, value))
    }
}
