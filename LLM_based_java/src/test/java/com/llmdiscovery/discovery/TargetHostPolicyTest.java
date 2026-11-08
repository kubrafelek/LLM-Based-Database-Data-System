package com.llmdiscovery.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same cases as the Python tests/unit/test_host_policy.py. */
class TargetHostPolicyTest {

    static final Map<String, List<String>> DNS = Map.of(
            "system-db", List.of("172.18.0.2"),
            "demo-db", List.of("172.18.0.3"),
            "alias-of-system", List.of("172.18.0.2"),
            "metadata.internal", List.of("169.254.169.254"),
            "localhost", List.of("127.0.0.1", "::1"),
            "db.example.com", List.of("203.0.113.10"),
            "rebind.example.com", List.of("203.0.113.11", "169.254.169.254"),
            "mapped.example.com", List.of("::ffff:169.254.169.254"));

    static List<InetAddress> fakeResolve(String host) throws UnknownHostException {
        List<String> ips = DNS.get(host);
        if (ips == null) {
            if (!host.matches("[0-9a-fA-F:.]+")) {
                throw new UnknownHostException(host);
            }
            ips = List.of(host); // IP literal
        }
        List<InetAddress> out = new ArrayList<>();
        for (String ip : ips) {
            out.add(InetAddress.getByName(ip));
        }
        return out;
    }

    static List<String> check(String host, String allowed) throws UnknownHostException {
        AppProperties props = new AppProperties();
        props.setSystemDbHost("system-db");
        props.setTargetDbAllowedHosts(allowed);
        return new TargetHostPolicy(props, TargetHostPolicyTest::fakeResolve).allowedAddresses(host).stream()
                .map(InetAddress::getHostAddress).toList();
    }

    static void assertDenied(String host, String allowed) {
        assertThatThrownBy(() -> check(host, allowed)).isInstanceOf(AppException.TargetHostNotAllowed.class)
                .hasMessage("Connections to this host are not allowed");
    }

    @Test
    void privateNetworkHostIsAllowedByDefault() throws Exception {
        assertThat(check("demo-db", "")).containsExactly("172.18.0.3");
    }

    @Test
    void publicHostIsAllowedByDefault() throws Exception {
        assertThat(check("db.example.com", "")).containsExactly("203.0.113.10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"system-db", "SYSTEM-DB.", "alias-of-system", "172.18.0.2"})
    void systemDatabaseIsNeverAllowed(String host) {
        assertDenied(host, "");
        assertDenied(host, host + ",172.18.0.0/16");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "metadata.internal", "169.254.169.254", "fe80::1", "0.0.0.0", "::", "224.0.0.1", "240.0.0.1",
        "mapped.example.com"})
    void linkLocalMulticastUnspecifiedAndReservedAreDenied(String host) {
        assertDenied(host, "");
        assertDenied(host, "0.0.0.0/0,::/0");
    }

    @Test
    void oneBadAddressDeniesTheHost() {
        assertDenied("rebind.example.com", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1", "::1"})
    void loopbackIsDeniedUnlessListed(String host) {
        assertDenied(host, "");
    }

    @Test
    void listedLoopbackIsAllowed() throws Exception {
        assertThat(check("localhost", "localhost")).containsExactly("127.0.0.1", "0:0:0:0:0:0:0:1");
        assertThat(check("127.0.0.1", "127.0.0.0/8")).containsExactly("127.0.0.1");
    }

    @Test
    void allowListRestrictsHosts() throws Exception {
        String allowed = "demo-db, 203.0.113.0/24";
        assertThat(check("demo-db", allowed)).containsExactly("172.18.0.3");
        assertThat(check("db.example.com", allowed)).containsExactly("203.0.113.10");
        assertDenied("10.1.2.3", allowed);
    }

    @Test
    void unknownHostThrowsUnknownHostException() {
        assertThatThrownBy(() -> check("no-such-host.invalid", "")).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void ipv6LiteralsAreBracketedForTheDriver() throws Exception {
        assertThat(TargetHostPolicy.jdbcHost(InetAddress.getByName("fd00::1"))).isEqualTo("[fd00:0:0:0:0:0:0:1]");
        assertThat(TargetHostPolicy.jdbcHost(InetAddress.getByName("10.0.0.1"))).isEqualTo("10.0.0.1");
    }
}
