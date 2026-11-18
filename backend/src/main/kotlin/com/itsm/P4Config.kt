package com.itsm

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

/** Switches on what P4 relies on: @Async (email sending) and @Scheduled (SSE heartbeat). Harmless if already enabled. */
@Configuration
@EnableAsync
@EnableScheduling
class P4Config
