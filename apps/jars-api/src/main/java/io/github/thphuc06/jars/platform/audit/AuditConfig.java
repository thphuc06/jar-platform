package io.github.thphuc06.jars.platform.audit;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Puts the transaction advisor outside the audit aspect ({@link AuditAspect#TRANSACTION_ORDER} is lower than the
 * aspect's order, and a lower value is the outer advisor).
 *
 * <p>Spring Boot enables transaction management itself, with order {@code LOWEST_PRECEDENCE}, which is the same as
 * the aspect's: with equal orders Spring does not promise which one is outer, and an audit aspect outside the
 * transaction would write its row in a transaction of its own, or none. Declaring {@code @EnableTransactionManagement}
 * here makes Boot's own configuration back off ({@code @ConditionalOnMissingBean(AbstractTransactionManagementConfiguration)}),
 * so the only change against Boot's default is the order. Class proxies ({@code proxyTargetClass = true}) are Boot's
 * default as well (property {@code spring.aop.proxy-target-class}); services here are classes without interfaces, and
 * CGLIB is what lets {@code @Transactional} and {@code @Audited} work on them.
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true, order = AuditAspect.TRANSACTION_ORDER)
class AuditConfig {}
