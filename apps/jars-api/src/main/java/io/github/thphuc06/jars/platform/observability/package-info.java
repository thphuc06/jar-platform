/**
 * Business metrics and tracing helpers shared by the business modules. Exposed as the named interface
 * {@code observability}. Traces and metrics leave the process over OTLP; on ECS an ADOT collector sidecar forwards them
 * to X-Ray and CloudWatch (docs/02-architecture/aws-infrastructure.md section 11).
 */
@NamedInterface("observability")
package io.github.thphuc06.jars.platform.observability;

import org.springframework.modulith.NamedInterface;
