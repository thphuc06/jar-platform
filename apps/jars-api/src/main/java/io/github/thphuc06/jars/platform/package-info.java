/**
 * Cross-cutting infrastructure (web/health, persistence setup, later security, error handling, audit,
 * observability). Not a business module: it must never depend on business modules.
 */
@ApplicationModule(displayName = "Platform (infrastructure)")
package io.github.thphuc06.jars.platform;

import org.springframework.modulith.ApplicationModule;
