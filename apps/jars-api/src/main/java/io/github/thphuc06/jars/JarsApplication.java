package io.github.thphuc06.jars;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Each direct sub-package of {@code io.github.thphuc06.jars} is an application module (Spring Modulith).
 * Only types in a module's root package are its public API; everything in sub-packages is internal.
 * {@code platform} holds cross-cutting infrastructure and is shared with every module.
 */
@SpringBootApplication
@Modulithic(systemName = "Jars Platform", sharedModules = "platform")
public class JarsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JarsApplication.class, args);
    }
}
