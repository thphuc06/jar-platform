package io.github.thphuc06.jars.platform.time;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @ApplicationModuleListener} is {@code @Async}: the listener runs on another thread after the publisher's
 * transaction commits. Spring Boot supplies the executor (virtual threads, see spring.threads.virtual.enabled).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {}
