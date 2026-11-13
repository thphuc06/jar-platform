/**
 * Audit log, "who did what, when" (F14, ADR-0025). Business modules mark a use case with {@link
 * io.github.thphuc06.jars.platform.audit.Audited} and describe it with {@link
 * io.github.thphuc06.jars.platform.audit.Audit}; the aspect writes the row in the business transaction. Exposed to
 * business modules as the named interface {@code audit}. The read endpoint and the table belong to this package, so
 * {@code platform} still depends on no business module.
 */
@NamedInterface("audit")
package io.github.thphuc06.jars.platform.audit;

import org.springframework.modulith.NamedInterface;
