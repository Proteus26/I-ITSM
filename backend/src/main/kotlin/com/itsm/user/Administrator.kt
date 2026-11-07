package com.itsm.user

import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity

@Entity
@DiscriminatorValue("ADMIN")
class Administrator(
    email: String,
    fullName: String,
    passwordHash: String,
) : User(email, fullName, passwordHash) {

    override val role: Role get() = Role.ADMIN
}
