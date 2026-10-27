package io.github.thphuc06.jars.infra;

import java.util.Optional;

/** One configuration for every stack test; the account id is a placeholder, never a real one. */
final class TestConfig {

    static final EnvConfig CONFIG = new EnvConfig(
            "test",
            "111111111111",
            "ap-southeast-1",
            "jars-test-prefix",
            "http://localhost:5173",
            "thphuc06/jar-platform",
            Optional.empty(),
            30);

    private TestConfig() {}
}
