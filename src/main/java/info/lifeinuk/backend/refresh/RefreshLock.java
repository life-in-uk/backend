package info.lifeinuk.backend.refresh;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single runner across application instances: a PostgreSQL session advisory lock held on one dedicated
 * pooled connection for the duration of the run. The run itself uses other connections and no ambient
 * transaction (acquisitions reject one). If the lock is held elsewhere the tick is SKIPPED.
 */
@Component
class RefreshLock {
    static final long UNDERGROUND_KEY = 0x4C49554B_54524C01L;
    static final long ROADS_KEY = 0x4C49554B_54524C02L;
    private static final Logger log = LoggerFactory.getLogger(RefreshLock.class);
    private final DataSource dataSource;

    RefreshLock(DataSource dataSource) { this.dataSource = dataSource; }

    RefreshPolicy.Outcome runExclusively(long key, Supplier<RefreshPolicy.Outcome> task) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException unavailable) {
            log.warn("Travel refresh lock unavailable: database connection failed");
            return RefreshPolicy.Outcome.FAILED;
        }
        boolean locked = false;
        try {
            connection.setAutoCommit(true);
            locked = query(connection, "SELECT pg_try_advisory_lock(?)", key);
            if (!locked) {
                return RefreshPolicy.Outcome.SKIPPED;
            }
            return task.get();
        } catch (SQLException lockFailure) {
            log.warn("Travel refresh lock could not be taken");
            return RefreshPolicy.Outcome.FAILED;
        } finally {
            release(connection, key, locked);
        }
    }

    private static void release(Connection connection, long key, boolean locked) {
        boolean clean = !locked;
        if (locked) {
            try {
                clean = query(connection, "SELECT pg_advisory_unlock(?)", key);
            } catch (SQLException unlockFailure) {
                clean = false;
            }
        }
        try {
            if (!clean) {
                // Never return a connection that may still hold the lock: discard it, ending its session.
                connection.abort(Runnable::run);
            }
            connection.close();
        } catch (SQLException ignored) {
            log.warn("Travel refresh lock connection could not be closed cleanly");
        }
    }

    private static boolean query(Connection connection, String sql, long key) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
