package io.github.thphuc06.jars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.infra.config.EnvConfig;
import io.github.thphuc06.jars.infra.config.EnvConfig.DbMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;

class EnvConfigTest {

    private static EnvConfig from(Map<String, Object> context) {
        return EnvConfig.from(new App(AppProps.builder().context(context).build()).getNode());
    }

    @Test
    void defaultsMatchTheDocumentedContext() {
        EnvConfig cfg = from(Map.of());
        assertThat(cfg.env()).isEqualTo("dev");
        assertThat(cfg.account()).isEqualTo("626212071763");
        assertThat(cfg.region()).isEqualTo("ap-southeast-1");
        assertThat(cfg.apiDesiredCount()).isEqualTo(1);
        assertThat(cfg.dbMode()).isEqualTo(DbMode.AURORA);
        assertThat(cfg.deployWeb()).isFalse();
        assertThat(cfg.imageTag()).isEmpty();
        assertThat(cfg.cloudFrontPrefixListId()).isEqualTo("pl-31a34658");
        assertThat(cfg.prefix()).isEqualTo("jars-dev");
        assertThat(cfg.name("alarms")).isEqualTo("jars-dev-alarms");
    }

    @Test
    void cliValuesArriveAsStringsAndAreParsed() {
        EnvConfig cfg = from(Map.of(
                "apiDesiredCount", "0",
                "deployWeb", "true",
                "dbMode", "RDS",
                "imageTag", " abc123 "));
        assertThat(cfg.apiDesiredCount()).isZero();
        assertThat(cfg.deployWeb()).isTrue();
        assertThat(cfg.dbMode()).isEqualTo(DbMode.RDS);
        assertThat(cfg.imageTag()).isEqualTo("abc123");
    }

    @Test
    void jsonValuesKeepTheirType() {
        EnvConfig cfg = from(Map.of("apiDesiredCount", 2, "deployWeb", true));
        assertThat(cfg.apiDesiredCount()).isEqualTo(2);
        assertThat(cfg.deployWeb()).isTrue();
    }

    @Test
    void aNonNumericDesiredCountIsRejectedWithTheKeyInTheMessage() {
        assertThatThrownBy(() -> from(Map.of("apiDesiredCount", "many")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiDesiredCount");
    }

    @Test
    void anUnknownRegionNeedsTheCloudFrontPrefixListIdExplicitly() {
        assertThatThrownBy(() -> from(Map.of("region", "eu-west-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cloudFrontPrefixListId");
        assertThat(from(Map.of("region", "eu-west-1", "cloudFrontPrefixListId", "pl-4fa04526"))
                        .cloudFrontPrefixListId())
                .isEqualTo("pl-4fa04526");
    }
}
