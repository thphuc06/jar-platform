/**
 * Shared kernel: tiny value types and helpers every module may use (ids, later Money, Month, problem types).
 * Keep it small and free of business logic, otherwise it becomes a hidden coupling point.
 */
@ApplicationModule(displayName = "Shared kernel")
package io.github.thphuc06.jars.shared;

import org.springframework.modulith.ApplicationModule;
