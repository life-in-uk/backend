package info.lifeinuk.backend.rail;

import java.util.List;

public record RailResponse(String stationName, String crs, String generatedAt, List<Service> services) {
    public record Location(String name, String crs) { }
    public record CallingPoint(String name, String crs, String scheduledTime, String estimatedTime, String actualTime,
            Boolean cancelled, String delayReason, String cancellationReason) { }
    public record CallingPointGroup(List<CallingPoint> callingPoints, String serviceType, Boolean serviceChangeRequired, Boolean associatedCancelled) { }
    public record Service(String scheduledArrival, String estimatedArrival, List<Location> origin, List<Location> destination,
            String platform, String operator, Boolean cancelled, String delayReason, String cancellationReason,
            List<CallingPointGroup> previousCallingPoints, List<CallingPointGroup> subsequentCallingPoints) { }
}
