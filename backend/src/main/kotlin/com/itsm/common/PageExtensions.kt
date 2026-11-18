package com.itsm.common

import com.itsm.user.PageResponse
import org.springframework.data.domain.Page

/** Maps a Spring Data page onto the project's existing PageResponse (com.itsm.user), so the JSON shape is shared. */
fun <T : Any> Page<T>.toPageResponse(): PageResponse<T> =
    PageResponse(content = content, page = number, size = size, totalElements = totalElements, totalPages = totalPages)
