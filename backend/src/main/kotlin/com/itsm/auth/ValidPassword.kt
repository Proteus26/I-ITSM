package com.itsm.auth

import com.itsm.user.PasswordPolicy
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/** For NEW passwords: 8 to 72 characters and at most 72 UTF-8 bytes (the BCrypt limit). Null is valid; combine with NotBlank. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ValidPasswordValidator::class])
annotation class ValidPassword(
    val message: String = PasswordPolicy.MESSAGE,
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class ValidPasswordValidator : ConstraintValidator<ValidPassword, String?> {
    override fun isValid(value: String?, context: ConstraintValidatorContext): Boolean =
        value == null || PasswordPolicy.violation(value) == null
}
