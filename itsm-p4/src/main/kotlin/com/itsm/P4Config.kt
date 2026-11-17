package com.itsm

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

/** Switches on what P4 relies on. Both annotations are harmless if another module already enables them. */
@Configuration
@EnableScheduling
@EnableMethodSecurity
class P4Config
