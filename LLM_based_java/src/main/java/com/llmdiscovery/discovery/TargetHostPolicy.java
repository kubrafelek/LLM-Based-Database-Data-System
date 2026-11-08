package com.llmdiscovery.discovery;

import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which hosts a target database connection may reach (guards POST /db/metadata against SSRF).
 * Same rules as the Python service (app/infrastructure/database/host_policy.py):
 *
 * <ul>
 *   <li>the system database is never allowed (same hostname, or any shared address);
 *   <li>link-local (incl. cloud metadata), multicast, unspecified and reserved addresses never;
 *   <li>loopback only when TARGET_DB_ALLOWED_HOSTS lists it;
 *   <li>when TARGET_DB_ALLOWED_HOSTS is set, the host must match an entry (hostname, IP or CIDR).
 * </ul>
 *
 * Every resolved address must pass, and the connection goes to those addresses (no DNS rebinding).
 */
@Component
public class TargetHostPolicy {

    /** Resolves a host to all its addresses; an IP literal resolves to itself without DNS. */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    // IP literal (optionally with a /prefix): digits and dots, or anything with a colon.
    private static final Pattern IP_LITERAL = Pattern.compile("^([0-9.]+|[0-9a-fA-F:.]*:[0-9a-fA-F:.]*)(/\\d{1,3})?$");

    // IPv6 networks Python's ipaddress treats as reserved (is_reserved), as prefix/length pairs.
    private static final String[][] IPV6_RESERVED = {
        {"::", "8"}, {"100::", "8"}, {"200::", "7"}, {"400::", "6"}, {"800::", "5"}, {"1000::", "4"},
        {"4000::", "3"}, {"6000::", "3"}, {"8000::", "3"}, {"a000::", "3"}, {"c000::", "3"},
        {"e000::", "4"}, {"f000::", "5"}, {"f800::", "6"}, {"fe00::", "9"},
    };

    private final AppProperties properties;
    private final Resolver resolver;

    @Autowired
    public TargetHostPolicy(AppProperties properties) {
        this(properties, host -> List.of(InetAddress.getAllByName(host)));
    }

    TargetHostPolicy(AppProperties properties, Resolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    private static String normalize(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /**
     * The addresses to connect to. Throws TargetHostNotAllowed, or UnknownHostException when the host
     * does not resolve.
     */
    public List<InetAddress> allowedAddresses(String host) throws UnknownHostException {
        String systemHost = properties.getSystemDbHost();
        if (!systemHost.isBlank() && normalize(host).equals(normalize(systemHost))) {
            throw new AppException.TargetHostNotAllowed();
        }
        List<InetAddress> addresses = new ArrayList<>(new LinkedHashSet<>(resolver.resolve(host)));
        if (addresses.isEmpty()) {
            throw new UnknownHostException(host);
        }
        Set<InetAddress> systemAddresses = new HashSet<>();
        if (!systemHost.isBlank()) {
            try {
                systemAddresses.addAll(resolver.resolve(systemHost));
            } catch (UnknownHostException e) {
                // compared by name only
            }
        }
        AllowList allowList = new AllowList(properties.targetAllowedHosts());
        for (InetAddress address : addresses) {
            boolean listed = allowList.matches(host, address);
            if (systemAddresses.contains(address)
                    || alwaysDenied(address)
                    || (address.isLoopbackAddress() && !listed)
                    || (!allowList.isEmpty() && !listed)) {
                throw new AppException.TargetHostNotAllowed();
            }
        }
        return addresses;
    }

    static boolean alwaysDenied(InetAddress address) {
        return address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()
                || (isReserved(address) && !address.isLoopbackAddress());
    }

    static boolean isReserved(InetAddress address) {
        if (address instanceof Inet4Address) {
            return (address.getAddress()[0] & 0xff) >= 240; // 240.0.0.0/4
        }
        for (String[] net : IPV6_RESERVED) {
            if (inNetwork(address, literal(net[0]), Integer.parseInt(net[1]))) {
                return true;
            }
        }
        return false;
    }

    static boolean inNetwork(InetAddress address, InetAddress network, int prefix) {
        byte[] a = address.getAddress();
        byte[] n = network.getAddress();
        if (a.length != n.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != n[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xff << (8 - rest)) & 0xff;
        return (a[full] & mask) == (n[full] & mask);
    }

    private static InetAddress literal(String ip) {
        try {
            return InetAddress.getByName(ip); // a literal: no DNS lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(ip, e);
        }
    }

    /** The host string for the JDBC driver: IPv6 literals in brackets. */
    static String jdbcHost(InetAddress address) {
        String ip = address.getHostAddress();
        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }
        return address instanceof Inet6Address ? "[" + ip + "]" : ip;
    }

    private static final class AllowList {
        private final Set<String> hostnames = new HashSet<>();
        private final List<Object[]> networks = new ArrayList<>(); // {InetAddress, Integer prefix}

        AllowList(List<String> entries) {
            for (String entry : entries) {
                if (IP_LITERAL.matcher(entry).matches()) {
                    String[] parts = entry.split("/", 2);
                    InetAddress network = literal(parts[0]);
                    int max = network.getAddress().length * 8;
                    networks.add(new Object[] {network, parts.length == 2 ? Math.min(Integer.parseInt(parts[1]), max) : max});
                } else {
                    hostnames.add(normalize(entry));
                }
            }
        }

        boolean isEmpty() {
            return hostnames.isEmpty() && networks.isEmpty();
        }

        boolean matches(String host, InetAddress address) {
            if (hostnames.contains(normalize(host))) {
                return true;
            }
            for (Object[] net : networks) {
                if (inNetwork(address, (InetAddress) net[0], (Integer) net[1])) {
                    return true;
                }
            }
            return false;
        }
    }
}
