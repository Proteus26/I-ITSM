package com.itsm.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class MetaController {

    data class PingResponse(val status: String, val service: String, val time: Instant)

    @GetMapping("/ping")
    fun ping() = PingResponse(status = "ok", service = "itsm-backend", time = Instant.now())
}
