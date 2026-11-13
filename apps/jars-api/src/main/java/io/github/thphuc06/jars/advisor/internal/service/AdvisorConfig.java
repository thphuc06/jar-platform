package io.github.thphuc06.jars.advisor.internal.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdvisorProperties.class)
class AdvisorConfig {}
