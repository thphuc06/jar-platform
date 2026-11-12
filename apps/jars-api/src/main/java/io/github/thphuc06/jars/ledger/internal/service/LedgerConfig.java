package io.github.thphuc06.jars.ledger.internal.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
class LedgerConfig {}
