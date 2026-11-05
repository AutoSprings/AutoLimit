package com.autolimit.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TrustedProxyClientIpResolverTest {
    @ParameterizedTest
    @ValueSource(strings = {"X-Forwarded-For", "Forwarded"})
    void frameworkForwardingCannotOverrideTheTransportPeer(String header) throws Exception {
        var request = request("203.0.113.20", null);
        request.addHeader(header, header.equals("Forwarded") ? "for=198.51.100.99" : "198.51.100.99");
        var address = new java.util.concurrent.atomic.AtomicReference<String>();
        new org.springframework.web.filter.ForwardedHeaderFilter().doFilter(request,
                new org.springframework.mock.web.MockHttpServletResponse(), (wrapped, response) ->
                        address.set(resolver.resolve(new jakarta.servlet.http.HttpServletRequestWrapper(
                                (jakarta.servlet.http.HttpServletRequest) wrapped))));
        assertThat(address.get()).isEqualTo("203.0.113.20");
    }

    @Test
    void frameworkForwardingPreservesOurTrustedProxyChainPolicy() throws Exception {
        var request = request("10.0.0.2", "198.51.100.99, 203.0.113.20, 10.0.0.1");
        var address = new java.util.concurrent.atomic.AtomicReference<String>();
        new org.springframework.web.filter.ForwardedHeaderFilter().doFilter(request,
                new org.springframework.mock.web.MockHttpServletResponse(), (wrapped, response) ->
                        address.set(resolver.resolve((jakarta.servlet.http.HttpServletRequest) wrapped)));
        assertThat(address.get()).isEqualTo("203.0.113.20");
    }

    private final ClientIpResolver resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/24"), 8192, 32);

    @Test
    void directClientCannotChooseItsAddressThroughAHeader() {
        assertThat(resolver.resolve(request("203.0.113.20", "198.51.100.99"))).isEqualTo("203.0.113.20");
    }

    @Test
    void stopsAtFirstUntrustedAddressFromTheRight() {
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.99, 203.0.113.20, 10.0.0.1")))
                .isEqualTo("203.0.113.20");
    }

    @Test
    void combinesRepeatedHeaderFieldsInOrder() {
        var request = request("10.0.0.2", "198.51.100.99, 203.0.113.20");
        request.addHeader("X-Forwarded-For", "10.0.0.1");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip", "localhost", "203.0.113.1:80", "[2001:db8::1]", "fe80::1%eth0",
            "", " ", ",203.0.113.1", "203.0.113.1,", "203.0.113.1,,10.0.0.1", "bad,203.0.113.1"})
    void rejectsMalformedTrustedHeaders(String header) {
        assertBadRequest(resolver, request("10.0.0.2", header));
    }

    @Test
    void missingHeaderUsesTrustedPeer() {
        assertThat(resolver.resolve(request("10.0.0.2", null))).isEqualTo("10.0.0.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad", "1,2,3,4,5,6"})
    void untrustedHeadersAreIgnoredBeforeValidation(String header) {
        var limited = new TrustedProxyClientIpResolver(List.of("10.0.0.0/24"), 1, 1);
        assertThat(limited.resolve(request("203.0.113.20", header))).isEqualTo("203.0.113.20");
    }

    @Test
    void normalizesDirectAndForwardedIpv6() {
        assertThat(resolver.resolve(request("2001:0DB8:0000:0000:0000:0000:0000:0001", null)))
                .isEqualTo("2001:db8::1");
        var ipv6 = new TrustedProxyClientIpResolver(List.of("fd00::/64"), 8192, 32);
        assertThat(ipv6.resolve(request("fd00::2", "2001:0DB8:0:0:0:0:0:1, fd00::1"))).isEqualTo("2001:db8::1");
    }

    @ParameterizedTest
    @CsvSource({"10.0.0.128/25,10.0.0.127,false", "10.0.0.128/25,10.0.0.128,true",
            "10.0.0.128/25,10.0.0.255,true", "10.0.0.128/25,10.0.1.0,false",
            "10.0.0.129/25,10.0.0.128,true", "10.0.0.2,10.0.0.2,true", "10.0.0.2,10.0.0.3,false",
            "0.0.0.0/0,192.0.2.1,true", "0.0.0.0/0,2001:db8::1,false",
            "fd00::/127,fd00::1,true", "fd00::/127,fd00::2,false",
            "fd00::1/128,fd00::1,true", "fd00::1/128,fd00::2,false",
            "::/0,2001:db8::1,true", "::/0,192.0.2.1,false"})
    void respectsCidrBoundariesAndAddressFamilies(String cidr, String peer, boolean trusted) {
        var configured = new TrustedProxyClientIpResolver(List.of(cidr), 8192, 32);
        assertThat(configured.resolve(request(peer, "198.51.100.99"))).isEqualTo(trusted ? "198.51.100.99" : peer);
    }

    @Test
    void headerSizeLimitIncludesRepeatedFieldSeparators() {
        var limited = new TrustedProxyClientIpResolver(List.of("10.0.0.2"), 15, 32);
        var exact = request("10.0.0.2", "1.1.1.1");
        exact.addHeader("X-Forwarded-For", "2.2.2.2");
        assertThat(limited.resolve(exact)).isEqualTo("2.2.2.2");
        var oversized = request("10.0.0.2", "1.1.1.1");
        oversized.addHeader("X-Forwarded-For", "2.2.2.2 ");
        assertBadRequest(limited, oversized);
        assertBadRequest(limited, request("10.0.0.2", "1.1.1.1,2.2.2.2  "));
        assertThat(limited.resolve(request("10.0.0.2", "1.1.1.1,2.2.2.2"))).isEqualTo("2.2.2.2");
    }

    @Test
    void hopLimitAppliesAcrossRepeatedFieldsAndEntireChain() {
        var limited = new TrustedProxyClientIpResolver(List.of("10.0.0.0/24"), 8192, 2);
        assertThat(limited.resolve(request("10.0.0.2", "203.0.113.20,10.0.0.1"))).isEqualTo("203.0.113.20");
        var oversized = request("10.0.0.2", "198.51.100.99,203.0.113.20");
        oversized.addHeader("X-Forwarded-For", "10.0.0.1");
        assertBadRequest(limited, oversized);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "localhost", "203.0.113.1:80", "fe80::1%1", "[::1]"})
    void rejectsInvalidPeer(String peer) {
        assertBadRequest(resolver, request(peer, "203.0.113.20"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "localhost", "10.0.0.1/33", "::1/129", "10.0.0.1/-1",
            "10.0.0.1/", "10.0.0.1/a", "10.0.0.1/24/1", "fe80::1%1", "[::1]"})
    void rejectsInvalidProxyConfiguration(String proxy) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TrustedProxyClientIpResolver(Arrays.asList(proxy), 8192, 32));
    }

    @Test
    void rejectsNullProxyListAndNonpositiveLimits() {
        assertThatNullPointerException().isThrownBy(() -> new TrustedProxyClientIpResolver(null, 8192, 32));
        for (int invalid : new int[] {0, -1}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new TrustedProxyClientIpResolver(List.of(), invalid, 32));
            assertThatIllegalArgumentException().isThrownBy(() -> new TrustedProxyClientIpResolver(List.of(), 8192, invalid));
        }
    }

    @Test
    void cachesOnlyWithinRequestAndResolverInstance() {
        var request = request("10.0.0.2", "203.0.113.20");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "198.51.100.99");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.20");
        assertThat(new TrustedProxyClientIpResolver(List.of(), 8192, 32).resolve(request)).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.99"))).isEqualTo("198.51.100.99");
    }

    private static void assertBadRequest(ClientIpResolver resolver, MockHttpServletRequest request) {
        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }

    private static MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        return request;
    }
}
