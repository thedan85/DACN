package com.project.dacn.integration.realtime;

import com.project.dacn.common.time.DatabaseClock;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RealtimeAdmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:15:30.987Z");
    private static KeyPair keyPair;

    private RealtimeProperties properties;
    private RealtimeAdmissionRepository repository;
    private DatabaseClock clock;
    private RealtimeAdmissionService service;

    @BeforeAll
    static void generateSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        properties = new RealtimeProperties();
        properties.setEnabled(true);
        properties.setPrivateKey(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        properties.setSigningKeyId("admission-test");
        repository = mock(RealtimeAdmissionRepository.class);
        clock = mock(DatabaseClock.class);
        when(clock.now()).thenReturn(NOW);
        when(repository.lockControl()).thenReturn(new RealtimeAdmissionRepository.ControlRow(true, 1000));
        when(repository.findActiveProductIds(anyList()))
                .thenAnswer(call -> new LinkedHashSet<>(call.<List<String>>getArgument(0)));
        when(repository.countUnretiredStockLeases(anyList(), any())).thenReturn(Map.of());
        service = new RealtimeAdmissionService(properties,
                new SupabaseRealtimeTokenService(properties, clock), clock, repository);
    }

    @Test
    void disabledRealtimeDoesNotQueryTheDatabase() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.issue(RealtimePrincipal.guest(), List.of("p1")))
                .isInstanceOf(RealtimeUnavailableException.class);
        verifyNoInteractions(repository, clock);
    }

    @ParameterizedTest
    @MethodSource("unavailableControls")
    void missingPausedOrExhaustedControlDoesNotMintOrInsert(RealtimeAdmissionRepository.ControlRow row) {
        when(repository.lockControl()).thenReturn(row);

        assertThatThrownBy(() -> service.issue(RealtimePrincipal.guest(), List.of("p1")))
                .isInstanceOf(RealtimeUnavailableException.class);
        verify(repository, never()).findActiveProductIds(anyList());
        verify(repository, never()).insertLease(any(), any(), anyList(), anyBoolean(), any(), any());
        verifyNoInteractions(clock);
    }

    static Stream<RealtimeAdmissionRepository.ControlRow> unavailableControls() {
        return Stream.of(null, new RealtimeAdmissionRepository.ControlRow(false, 1000),
                new RealtimeAdmissionRepository.ControlRow(true, 0));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void malformedRequestsFailBeforeAnySqlEvenWhenDisabled(List<String> requested) {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.issue(RealtimePrincipal.guest(), requested))
                .isInstanceOf(RealtimeRequestValidationException.class)
                .hasMessage("invalid realtime credential request");
        verifyNoInteractions(repository, clock);
    }

    static Stream<List<String>> invalidRequests() {
        return Stream.of(null, Arrays.asList("p1", null), List.of(""), List.of(" \t\n"),
                List.of("x".repeat(256)), ids(25));
    }

    @Test
    void twentyFiveRawEntriesWithOneDuplicateAdmitTwentyFourUniqueInFirstOccurrenceOrder() {
        List<String> requested = new ArrayList<>(ids(24));
        requested.add(1, "p0");

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(), requested);

        assertThat(response.admittedProductIds()).containsExactlyElementsOf(ids(24));
        assertThat(claims(response).get("stock_product_ids", List.class)).containsExactlyElementsOf(ids(24));
        verify(repository).findActiveProductIds(ids(24));
    }

    @Test
    void duplicatesDoNotConsumeAdmissionSlotsAndIdsAreNotRewritten() {
        properties.setMaxStockTopics(2);
        when(repository.countUnretiredStockLeases(List.of(" p1 ", "p2", "p3"), NOW))
                .thenReturn(Map.of(" p1 ", 19L));

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(),
                List.of(" p1 ", " p1 ", "p2", "p3"));

        assertThat(response.admittedProductIds()).containsExactly(" p1 ", "p2");
    }

    @Test
    void onlyVisibleProductsBecomeSignedScopesAndHiddenAndUnknownAreIndistinguishable() {
        when(repository.findActiveProductIds(anyList())).thenReturn(Set.of("active"));

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(),
                List.of("draft", "deleted", "active", "unknown"));

        assertThat(response.admittedProductIds()).containsExactly("active");
        assertThat(claims(response).get("stock_product_ids", List.class)).containsExactly("active");
        verify(repository).countUnretiredStockLeases(List.of("active"), NOW);
    }

    @Test
    void fullProductsAreOmittedWhileNineteenLeasesLeaveOneSlot() {
        when(repository.countUnretiredStockLeases(List.of("full", "free", "overfull"), NOW))
                .thenReturn(Map.of("full", 20L, "free", 19L, "overfull", 21L));

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(),
                List.of("full", "free", "overfull"));

        assertThat(response.admittedProductIds()).containsExactly("free");
    }

    @Test
    void countsUseDatabaseTimeSampledAfterTheControlLock() {
        service.issue(RealtimePrincipal.authenticated(42), List.of("p1"));

        InOrder order = inOrder(repository, clock);
        order.verify(repository).lockControl();
        order.verify(clock).now();
        order.verify(repository).findActiveProductIds(List.of("p1"));
        order.verify(repository).countUnretiredStockLeases(List.of("p1"), NOW);
        order.verify(repository).countUnretiredInboxLeases(42, NOW);
        order.verify(clock).now();
    }

    @Test
    void guestNeverCountsOrReceivesInboxAndHasNoUserClaim() {
        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(), List.of());

        assertThat(response.inboxTopic()).isNull();
        assertThat(claims(response).get("inbox_allowed", Boolean.class)).isFalse();
        assertThat(claims(response)).doesNotContainKey("app_user_id");
        verify(repository, never()).countUnretiredInboxLeases(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 19, 20, 21})
    void authenticatedInboxUsesOnlyItsInternalAccountAndTopicCap(long count) {
        when(repository.countUnretiredInboxLeases(42, NOW)).thenReturn(count);

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.authenticated(42), List.of());

        assertThat(response.inboxTopic()).isEqualTo(count < 20 ? "inbox:42" : null);
        assertThat(claims(response).get("app_user_id", String.class)).isEqualTo("42");
        assertThat(claims(response).get("inbox_allowed", Boolean.class)).isEqualTo(count < 20);
        verify(repository).countUnretiredInboxLeases(42, NOW);
    }

    @Test
    void responseAndLeaseUseTheSignedExpiryJtiScopesAndThirtySecondRetirementGrace() {
        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.authenticated(42), List.of("p2", "p1"));
        Claims signed = claims(response);

        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-11-04T10:20:30Z"));
        assertThat(response.expiresAt()).isEqualTo(signed.getExpiration().toInstant());
        verify(repository).insertLease(UUID.fromString(signed.getId()), 42L, List.of("p2", "p1"),
                true, Instant.parse("2026-11-04T10:20:30Z"), Instant.parse("2026-11-04T10:21:00Z"));
    }

    @Test
    void guestLeasePersistsNoInboxGrantOrUserId() {
        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.guest(), List.of("p1"));

        verify(repository).insertLease(eq(UUID.fromString(claims(response).getId())), isNull(),
                eq(List.of("p1")), eq(false), eq(response.expiresAt()), eq(response.expiresAt().plusSeconds(30)));
    }

    @Test
    void oversizedConfiguredRequestCapCannotWidenTheTwentyFourUniqueIdEnvelope() {
        properties.setMaxRequestedProducts(100);
        properties.setMaxStockTopics(100);

        assertThatThrownBy(() -> service.issue(RealtimePrincipal.guest(), ids(25)))
                .isInstanceOf(RealtimeRequestValidationException.class);
        verifyNoInteractions(repository, clock);
    }

    @Test
    void oversizedConfiguredReceiverCapCannotWidenStockOrInboxEnvelope() {
        properties.setMaxReceiversPerTopic(100);
        when(repository.countUnretiredStockLeases(List.of("p1"), NOW)).thenReturn(Map.of("p1", 20L));
        when(repository.countUnretiredInboxLeases(42, NOW)).thenReturn(20L);

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.authenticated(42), List.of("p1"));

        assertThat(response.admittedProductIds()).isEmpty();
        assertThat(response.inboxTopic()).isNull();
        assertThat(claims(response).get("stock_product_ids", List.class)).isEmpty();
        assertThat(claims(response).get("inbox_allowed", Boolean.class)).isFalse();
    }

    @Test
    void smallerConfiguredCapsRemainEffective() {
        properties.setMaxRequestedProducts(2);
        properties.setMaxStockTopics(1);
        properties.setMaxReceiversPerTopic(1);
        when(repository.countUnretiredStockLeases(List.of("full", "free"), NOW)).thenReturn(Map.of("full", 1L));
        when(repository.countUnretiredInboxLeases(42, NOW)).thenReturn(1L);

        RealtimeCredentialResponse response = service.issue(RealtimePrincipal.authenticated(42), List.of("full", "free"));

        assertThat(response.admittedProductIds()).containsExactly("free");
        assertThat(response.inboxTopic()).isNull();
        assertThatThrownBy(() -> service.issue(RealtimePrincipal.guest(), ids(3)))
                .isInstanceOf(RealtimeRequestValidationException.class);
    }

    @Test
    void inconsistentPrincipalCannotSmuggleAGuestIdentityOrMissingAuthenticatedIdentity() {
        assertThatThrownBy(() -> new RealtimePrincipal(42L, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimePrincipal(null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealtimePrincipal.authenticated(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealtimePrincipal.authenticated(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private Claims claims(RealtimeCredentialResponse response) {
        return Jwts.parser().verifyWith(keyPair.getPublic()).clock(() -> Date.from(NOW))
                .build().parseSignedClaims(response.accessToken()).getPayload();
    }

    private static List<String> ids(int count) {
        return IntStream.range(0, count).mapToObj(index -> "p" + index).toList();
    }
}
