package fr.redteams.archi.mcp.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.regex.Pattern;

/**
 * Listening address handling. Only IP literals and "localhost" are accepted,
 * so that nothing ever triggers a DNS lookup (preferences are edited on the UI thread).
 */
public final class BindAddresses {

    public static final String LOOPBACK = "127.0.0.1";
    public static final String ALL_INTERFACES = "0.0.0.0";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    /** Hex digits, colons and dots (IPv4-mapped), optional zone id: always parsed as a literal by the JDK. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*(%[\\w.-]+)?");

    private BindAddresses() {}

    /** Normalizes user input: blank means loopback, surrounding brackets of IPv6 literals are removed. */
    public static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return LOOPBACK;
        }
        String a = address.trim();
        if (a.startsWith("[") && a.endsWith("]")) {
            a = a.substring(1, a.length() - 1);
        }
        return a;
    }

    public static boolean isValid(String address) {
        return parse(address) != null;
    }

    /** True for 127.x.x.x, ::1 and localhost. Invalid input is not loopback. */
    public static boolean isLoopback(String address) {
        InetAddress a = parse(address);
        return a != null && a.isLoopbackAddress();
    }

    /**
     * Resolves an IP literal or "localhost" without DNS.
     *
     * @return the address, or null if the input is not acceptable
     */
    public static InetAddress parse(String address) {
        String a = normalize(address);
        if (a.equalsIgnoreCase("localhost")) {
            return InetAddress.getLoopbackAddress();
        }
        boolean ipv4 = IPV4.matcher(a).matches();
        if (ipv4) {
            for (String octet : a.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        }
        else if (!IPV6.matcher(a).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(a); // no lookup for literals
        }
        catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Host part of the URL clients should use: the loopback address, the given address,
     * or — when listening on all interfaces — this machine's LAN address.
     */
    public static String clientHost(String address) {
        InetAddress a = parse(address);
        if (a == null || a.isLoopbackAddress()) {
            return LOOPBACK;
        }
        if (a.isAnyLocalAddress()) {
            String lan = lanAddress();
            return lan != null ? lan : LOOPBACK;
        }
        String host = a.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }

    /** First IPv4 address of an active, non-loopback interface, site-local addresses first. */
    static String lanAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        if (a.isSiteLocalAddress()) {
                            return a.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = a.getHostAddress();
                        }
                    }
                }
            }
        }
        catch (SocketException e) {
            // no interface information
        }
        return fallback;
    }
}
