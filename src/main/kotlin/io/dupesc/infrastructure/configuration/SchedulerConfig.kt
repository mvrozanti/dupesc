package io.dupesc.infrastructure.configuration

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["dupe.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulerConfig
