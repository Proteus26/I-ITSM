package com.itsm.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import com.itsm.auth.AuthProperties
import java.time.Clock

@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class ClockConfig {
    /** Overridable in tests to control time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
