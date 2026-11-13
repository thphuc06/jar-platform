package io.github.thphuc06.jars.platform.security;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class CurrentUserWebConfig implements WebMvcConfigurer {

    private final CurrentUser currentUser;

    CurrentUserWebConfig(ObjectProvider<UserDirectory> directory) {
        this.currentUser = new CurrentUser(directory);
    }

    /** One instance for the argument resolver and for the audit aspect. */
    @Bean
    CurrentUser currentUser() {
        return currentUser;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new UserIdArgumentResolver(currentUser));
    }
}
