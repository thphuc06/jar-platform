package io.github.thphuc06.jars;

import org.springframework.boot.SpringApplication;

/**
 * Alternative to Docker Compose for local runs: starts the app against a throw-away Testcontainers Postgres.
 * Run this class from the IDE (test classpath) when you want a clean database every time.
 */
public class TestJarsApplication {

    public static void main(String[] args) {
        SpringApplication.from(JarsApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
