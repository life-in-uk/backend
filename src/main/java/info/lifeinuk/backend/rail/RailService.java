package info.lifeinuk.backend.rail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
final class RailService {
    private final RailClient client;
    private final Semaphore boardPermits = new Semaphore(4);
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    RailService(RailClient client) { this.client = client; }
    static String validate(String crs, Integer rows, Integer window) {
        if (crs == null || !crs.matches("[A-Za-z]{3}") || rows != null && (rows < 1 || rows > 9) || window != null && (window < -119 || window > 119)) throw new IllegalArgumentException();
        return crs.toUpperCase(Locale.ROOT);
    }
    RailResponse arrivals(String crs, Integer rows, Integer window) { return board(crs, rows, window, false); }
    RailResponse board(String crs, Integer rows, Integer window, boolean departures) {
        crs = validate(crs, rows, window);
        if (!boardPermits.tryAcquire()) throw new RailFailure(RailFailure.Reason.RATE_LIMIT);
        try {
            JsonNode root;
            try { root = json.readTree(departures ? client.departures(crs, rows, window) : client.arrivals(crs, rows, window)); }
            catch (JacksonException e) { throw invalid(); }
            if (root == null || !root.isObject()) throw invalid();
            String name = text(root, "locationName"), returnedCrs = text(root, "crs");
            if (name == null || !crs.equals(returnedCrs)) throw invalid();
            List<RailResponse.Service> services = array(root, "trainServices", s -> {
                object(s);
                return new RailResponse.Service(text(s,departures ? "std" : "sta"), text(s,departures ? "etd" : "eta"), locations(s,"origin"), locations(s,"destination"), text(s,"platform"), text(s,"operator"), bool(s,"isCancelled"), text(s,"delayReason"), text(s,"cancelReason"), groups(s,"previousCallingPoints"), groups(s,"subsequentCallingPoints"));
            });
            return new RailResponse(name, returnedCrs, text(root,"generatedAt"), services == null ? List.of() : services);
        } finally {
            boardPermits.release();
        }
    }
    private static List<RailResponse.Location> locations(JsonNode n, String field) { return array(n,field,x -> { object(x); return new RailResponse.Location(text(x,"locationName"),text(x,"crs")); }); }
    private static List<RailResponse.CallingPointGroup> groups(JsonNode n, String field) { return array(n,field,g -> { object(g); return new RailResponse.CallingPointGroup(array(g,"callingPoint",p -> { object(p); return new RailResponse.CallingPoint(text(p,"locationName"),text(p,"crs"),text(p,"st"),text(p,"et"),text(p,"at"),bool(p,"isCancelled"),text(p,"delayReason"),text(p,"cancelReason")); }),text(g,"serviceType"),bool(g,"serviceChangeRequired"),bool(g,"assocIsCancelled")); }); }
    private static void object(JsonNode n) { if (!n.isObject()) throw invalid(); }
    private static String text(JsonNode n, String key) { JsonNode v=n.get(key); if (v==null || v.isNull()) return null; if (!v.isString()) throw invalid(); return v.stringValue(); }
    private static Boolean bool(JsonNode n, String key) { JsonNode v=n.get(key); if (v==null || v.isNull()) return null; if (!v.isBoolean()) throw invalid(); return v.booleanValue(); }
    private static <T> List<T> array(JsonNode n, String key, java.util.function.Function<JsonNode,T> map) { JsonNode v=n.get(key); if (v==null || v.isNull()) return null; if (!v.isArray()) throw invalid(); var result=new ArrayList<T>(); for (JsonNode x:v) result.add(map.apply(x)); return List.copyOf(result); }
    private static RailFailure invalid() { return new RailFailure(RailFailure.Reason.INVALID_RESPONSE); }
}
