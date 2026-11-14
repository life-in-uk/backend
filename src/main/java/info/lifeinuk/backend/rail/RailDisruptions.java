package info.lifeinuk.backend.rail;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public record RailDisruptions(String stationName, String crs, String generatedAt, String direction,
        int unknownTimingCount, List<Disruption> disruptions) {
    public record Disruption(String status, Integer delayMinutes, String scheduledTime, String estimatedTime,
            List<RailResponse.Location> locations, String platform, String operator, String reason, boolean checkDelayRepay,
            List<RailResponse.Location> affectedStops) { }
    static RailDisruptions from(RailResponse board, String direction) {
        var disruptions = new ArrayList<Disruption>();
        int unknown = 0;
        for (var service : board.services()) {
            String estimate = service.estimatedArrival();
            boolean cancelled = Boolean.TRUE.equals(service.cancelled()) || "cancelled".equalsIgnoreCase(estimate);
            List<RailResponse.CallingPointGroup> groups = direction.equals("departures") ? service.subsequentCallingPoints() : service.previousCallingPoints();
            var affected = new ArrayList<RailResponse.Location>();
            var pointReasons = new java.util.LinkedHashSet<String>();
            boolean missingPointReason = false;
            boolean partial = false;
            if (groups != null) for (var group : groups) {
                if (Boolean.TRUE.equals(group.associatedCancelled())) partial = true;
                if (group.callingPoints() != null) for (var point : group.callingPoints()) {
                    if (Boolean.TRUE.equals(point.cancelled())) {
                        partial = true;
                        if (point.cancellationReason() != null && !point.cancellationReason().isBlank()) pointReasons.add(point.cancellationReason());
                        else missingPointReason = true;
                        if (point.name() != null && !point.name().isBlank() || point.crs() != null && !point.crs().isBlank()) {
                            var location = new RailResponse.Location(point.name(), point.crs());
                            if (!affected.contains(location)) affected.add(location);
                        }
                    }
                }
            }
            boolean explicitDelayed = "delayed".equalsIgnoreCase(estimate);
            Integer minutes = delay(service.scheduledArrival(), estimate);
            if (!cancelled && !partial && minutes == null && !explicitDelayed && !"on time".equalsIgnoreCase(estimate)) unknown++;
            if (!cancelled && !partial && !explicitDelayed && (minutes == null || minutes < 10)) continue;
            String reason = cancelled ? service.cancellationReason() : partial
                    ? pointReasons.size() == 1 && !missingPointReason ? pointReasons.iterator().next() : service.cancellationReason()
                    : service.delayReason();
            disruptions.add(new Disruption(cancelled ? "cancelled" : partial ? "partially_cancelled" : "delayed", minutes,
                    service.scheduledArrival(), estimate,
                    direction.equals("departures") ? service.destination() : service.origin(), service.platform(), service.operator(),
                    reason, cancelled || partial || minutes != null && minutes >= 15, List.copyOf(affected)));
        }
        return new RailDisruptions(board.stationName(), board.crs(), board.generatedAt(), direction, unknown, List.copyOf(disruptions));
    }
    private static Integer delay(String scheduled, String estimated) {
        if (scheduled == null || estimated == null || !scheduled.matches("\\d{2}:\\d{2}") || !estimated.matches("\\d{2}:\\d{2}")) return null;
        try {
            LocalTime s = LocalTime.parse(scheduled, DateTimeFormatter.ISO_LOCAL_TIME);
            LocalTime e = LocalTime.parse(estimated, DateTimeFormatter.ISO_LOCAL_TIME);
            int minutes = (int) ChronoUnit.MINUTES.between(s, e);
            // Board times carry no date. Select the nearest day, avoiding interpreting early running as a 24h delay.
            if (minutes < -720) minutes += 1440;
            if (minutes > 720) minutes -= 1440;
            return minutes;
        } catch (DateTimeParseException invalid) { return null; }
    }
}
