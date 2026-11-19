package info.lifeinuk.backend.weather;

import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Daily Met Office read budget in PostgreSQL ({@code weather_call_budget}), so restarts and container recreation do
 * not reset it. {@link #take} is one atomic statement: concurrent callers serialise on the day's row and the counter
 * never passes the limit. It fails closed: if the database cannot be reached, no read is allowed.
 *
 * <p>Its statements have their own {@link #TIMEOUT} (a JDBC query timeout: the driver cancels the statement), so a
 * locked row or a slow database cannot hold a weather refresh thread, and with it that city's refresh, indefinitely.
 * Other database users are unaffected.
 */
@Repository
class WeatherCallBudget {
    private static final Logger log = LoggerFactory.getLogger(WeatherCallBudget.class);
    static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final JdbcTemplate jdbc;

    @Autowired
    WeatherCallBudget(JdbcTemplate jdbc) { this(jdbc, TIMEOUT); }

    WeatherCallBudget(JdbcTemplate jdbc, Duration timeout) {
        if (jdbc == null) { this.jdbc = null; return; } // test doubles override every method
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
    }

    /** Counts one read for {@code day} (UTC) and returns true, or returns false when {@code limit} reads are used. */
    boolean take(LocalDate day, int limit) {
        if (limit <= 0) { return false; }
        try {
            return !jdbc.queryForList("""
                    INSERT INTO weather_call_budget AS b (day, calls) VALUES (?, 1)
                    ON CONFLICT (day) DO UPDATE SET calls = b.calls + 1 WHERE b.calls < ?
                    RETURNING calls""", Integer.class, day, limit).isEmpty();
        } catch (DataAccessException unavailable) {
            log.warn("Weather read budget unavailable ({}); not calling the Met Office", unavailable.getClass().getSimpleName());
            return false;
        }
    }

    /** Reads counted for {@code day} (0 if none). */
    int used(LocalDate day) {
        return jdbc.queryForList("SELECT calls FROM weather_call_budget WHERE day = ?", Integer.class, day)
                .stream().findFirst().orElse(0);
    }
}
