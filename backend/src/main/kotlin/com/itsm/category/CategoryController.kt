package com.itsm.category

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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

data class CategorySummary(val code: String, val name: String, val description: String?)

data class CategoryResponse(val code: String, val name: String, val description: String?, val active: Boolean)

data class CreateCategoryRequest(
    @field:NotBlank @field:Size(max = 100) val code: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 500) val description: String? = null,
)

/** Every field is optional; null means "leave unchanged". */
data class UpdateCategoryRequest(
    @field:Pattern(regexp = NOT_BLANK, message = "must not be blank") @field:Size(max = 100) val name: String? = null,
    @field:Size(max = 500) val description: String? = null,
    val active: Boolean? = null,
)

private fun Category.toSummary() = CategorySummary(code, name, description)
private fun Category.toResponse() = CategoryResponse(code, name, description, active)

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categories")
class CategoryController(private val service: CategoryService) {

    @Operation(summary = "Active categories", description = "For the report form's category list.")
    @GetMapping
    fun list(): List<CategorySummary> = service.listActive().map { it.toSummary() }
}

@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Categories (admin)")
class AdminCategoryController(private val service: CategoryService) {

    @Operation(summary = "All categories, including inactive ones")
    @GetMapping
    fun list(): List<CategoryResponse> = service.listAll().map { it.toResponse() }

    @Operation(summary = "Create a category", description = "The code is upper-cased; 409 if it exists.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateCategoryRequest): CategoryResponse = service.create(request).toResponse()

    @Operation(summary = "Rename, change the description, or (de)activate a category")
    @PatchMapping("/{code}")
    fun update(@PathVariable code: String, @Valid @RequestBody request: UpdateCategoryRequest): CategoryResponse =
        service.update(code, request).toResponse()
}
