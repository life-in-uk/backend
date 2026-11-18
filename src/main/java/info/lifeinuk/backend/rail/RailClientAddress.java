package info.lifeinuk.backend.rail;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The address a Rail request budget is kept for.
 *
 * <p>In production every request reaches the app from the nginx container (CloudFront → nginx → app), so the
 * servlet remote address is the same for all viewers and a per-client budget keyed on it would be one budget
 * for everyone. CloudFront adds {@code CloudFront-Viewer-Address} ("ip:port") through the distribution's
 * origin request policy; viewers cannot set it, and nginx already keys its own rate limits on it. The header
 * is honoured only when the request comes from a private or loopback address (nginx on the Compose network,
 * or an operator tunnel); otherwise, or when it is missing or malformed, the remote address is used.
 */
final class RailClientAddress {
    static final String VIEWER_ADDRESS = "CloudFront-Viewer-Address";
    private static final Pattern IPV4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,39}");

    private RailClientAddress() { }

    static String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String viewer = viewerIp(request.getHeader(VIEWER_ADDRESS));
        return viewer != null && fromPrivateNetwork(remote) ? viewer : remote;
    }

    /** The IP of a "ip:port" value (IPv6 without brackets, as CloudFront sends it), or null. */
    static String viewerIp(String header) {
        if (header == null) return null;
        String value = header.trim();
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return null;
        String port = value.substring(colon + 1);
        String ip = value.substring(0, colon);
        if (!port.chars().allMatch(Character::isDigit) || port.length() > 5) return null;
        if (IPV4.matcher(ip).matches()) return ip;
        return ip.indexOf(':') >= 0 && IPV6.matcher(ip).matches() ? ip : null;
    }

    private static boolean fromPrivateNetwork(String remote) {
        if (remote == null || !(IPV4.matcher(remote).matches() || IPV6.matcher(remote).matches())) return false;
        try {
            // An IP literal, so no name lookup happens.
            InetAddress address = InetAddress.getByName(remote);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || uniqueLocalV6;
        } catch (UnknownHostException invalid) {
            return false;
        }
    }
}
