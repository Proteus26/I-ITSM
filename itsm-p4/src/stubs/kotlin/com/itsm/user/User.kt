package com.itsm.user

import jakarta.persistence.*

/**
 * STUB: owned by P1. Delete when P1's user entity is merged. P4 needs: id, username, fullName, email,
 * employeeCode (shown in the audit timeline, e.g. "E102"), role, active.
 */
@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false, unique = true)
    var username: String = "",

    @Column(name = "full_name", nullable = false)
    var fullName: String = "",

    @Column(nullable = false)
    var email: String = "",

    @Column(name = "employee_code")
    var employeeCode: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var role: Role = Role.EMPLOYEE,

    @Column(nullable = false)
    var active: Boolean = true,
)

enum class Role { EMPLOYEE, TECHNICIAN, TEAM_LEAD, ADMIN, IT_MANAGER }
