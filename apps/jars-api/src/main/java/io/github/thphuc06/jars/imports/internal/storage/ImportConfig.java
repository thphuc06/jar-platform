package io.github.thphuc06.jars.imports.internal.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportProperties.class)
class ImportConfig {}
