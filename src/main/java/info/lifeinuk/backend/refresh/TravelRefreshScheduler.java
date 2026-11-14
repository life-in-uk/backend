package info.lifeinuk.backend.refresh;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Starts only the schedules explicitly enabled in configuration, after startup bootstraps have run. */
@Component
@EnableConfigurationProperties(TravelRefreshProperties.class)
class TravelRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(TravelRefreshScheduler.class);
    private final TravelRefreshProperties properties;
    private final TravelRefreshTasks tasks;
    private final RefreshLock lock;
    private final List<RefreshLoop> loops = new ArrayList<>();

    TravelRefreshScheduler(TravelRefreshProperties properties, TravelRefreshTasks tasks, RefreshLock lock) {
        this.properties = properties;
        this.tasks = tasks;
        this.lock = lock;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (!loops.isEmpty()) {
            return;
        }
        var underground = properties.underground();
        var roads = properties.roads();
        if (underground.enabled()) {
            start("underground", underground,
                    () -> lock.runExclusively(RefreshLock.UNDERGROUND_KEY, tasks::underground));
        }
        if (roads.enabled()) {
            start("roads", roads, () -> lock.runExclusively(RefreshLock.ROADS_KEY, tasks::roads));
        }
        if (loops.isEmpty()) {
            log.info("Travel refresh is disabled; no provider polling is scheduled");
        }
    }

    private void start(String name, TravelRefreshProperties.Schedule schedule,
            java.util.function.Supplier<RefreshPolicy.Outcome> task) {
        var loop = new RefreshLoop(name, new RefreshPolicy(schedule.interval(), schedule.maxBackoff()),
                schedule.maxRuns(), task);
        loops.add(loop);
        loop.start(schedule.initialDelay());
    }

    synchronized int scheduleCount() { return loops.size(); }

    @PreDestroy
    synchronized void stop() {
        loops.forEach(RefreshLoop::stop);
    }
}
