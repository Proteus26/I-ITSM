package com.itsm.category

import com.itsm.common.exception.ItsmException
import com.itsm.common.exception.NotFoundException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class CategoryConflictException(message: String) : ItsmException(message, HttpStatus.CONFLICT)

/** Category administration. Categories are deactivated, never deleted, because incidents and skills refer to their codes. */
@Service
@Transactional
class CategoryService(private val repository: CategoryRepository) {

    @Transactional(readOnly = true)
    fun listActive(): List<Category> = repository.findByActiveTrueOrderByNameAsc()

    @Transactional(readOnly = true)
    fun listAll(): List<Category> = repository.findAll().sortedBy { it.name }

    /** The code is normalised (upper case); an invalid code is a 400, an existing one a 409. */
    fun create(request: CreateCategoryRequest): Category {
        val category = Category(request.code, request.name, request.description)
        if (repository.existsById(category.code)) throw CategoryConflictException("Category '${category.code}' already exists")
        return repository.saveAndFlush(category)
    }

    /** Null fields leave the value unchanged; a blank description clears it. The code cannot change. */
    fun update(code: String, request: UpdateCategoryRequest): Category {
        val category = repository.findById(Category.normalizeCode(code)).orElseThrow { NotFoundException("Category $code not found") }
        category.update(request.name ?: category.name, request.description ?: category.description)
        when (request.active) {
            true -> category.activate()
            false -> category.deactivate()
            null -> Unit
        }
        return category
    }
}
