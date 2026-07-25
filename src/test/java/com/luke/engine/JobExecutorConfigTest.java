package com.luke.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * #28: the job executor must be explicitly configured and sized so background jobs can never starve
 * the request path of DB connections. This pins the config in {@code application-postgres.yml} (the
 * profile that runs against a real pooled Postgres) and enforces the documented invariant
 * job-executor max-pool-size &lt; Hikari maximum-pool-size.
 */
class JobExecutorConfigTest {

    private static final Path PG = Path.of("src/main/resources/application-postgres.yml");

    @Test
    void jobExecutorIsExplicitlyConfigured() throws Exception {
        String yml = Files.readString(PG);
        assertThat(yml)
                .contains("job-execution:")
                .contains("core-pool-size:")
                .contains("queue-capacity:")
                .contains("max-jobs-per-acquisition:")
                .contains("lock-time-in-millis:")
                .contains("wait-time-in-millis:")
                .contains("deployment-aware:");
    }

    @Test
    void jobPoolStaysBelowTheHikariPoolSoJobsCannotStarveRequests() throws Exception {
        String yml = Files.readString(PG);
        int jobMax = intProp(yml, "max-pool-size");        // fluxnova.bpm.job-execution.max-pool-size
        int hikariMax = intProp(yml, "maximum-pool-size"); // spring.datasource.hikari.maximum-pool-size
        assertThat(jobMax)
                .as("job-executor max-pool-size (%d) must stay below the Hikari pool (%d)", jobMax, hikariMax)
                .isLessThan(hikariMax);
    }

    private static int intProp(String yml, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*(\\d+)").matcher(yml);
        assertThat(m.find()).as("%s present", key).isTrue();
        return Integer.parseInt(m.group(1));
    }
}
