package com.itsm.user

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity

@Entity
@DiscriminatorValue("EMPLOYEE")
class Employee(
    email: String,
    fullName: String,
    passwordHash: String,
    department: String? = null,
) : User(email, fullName, passwordHash) {

    @Column(name = "department")
    var department: String? = department?.trim()?.ifEmpty { null }
        protected set

    override val role: Role get() = Role.EMPLOYEE

    fun changeDepartment(department: String?) {
        this.department = department?.trim()?.ifEmpty { null }
    }
}
