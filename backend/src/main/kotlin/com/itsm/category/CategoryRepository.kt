package com.itsm.category

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<Category, String> {
    fun findByActiveTrueOrderByNameAsc(): List<Category>

    fun findByActiveTrue(): List<Category>
}
