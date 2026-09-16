package dev.goodrich.althalt.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagementTest {
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CAROL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String SERVER = "example.org:25565";
    private static final String OTHER = "other.org:25565";
    private static final long NOW = 1_800_000_000_000L;
    @TempDir Path directory;

    private GuardStore store(long time) { return new GuardStore(directory, Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC)); }
    private GuardStore store() { return store(NOW); }
    private GuardStore.BlockEntry uuidBlock(GuardStore store, String server, UUID account) throws Exception {
        var error = assertThrows(BlockedException.class, () -> store.requireLock(server, account));
        return store.recordBlock(server, account, "Blocked player", error, false);
    }
    private GuardStore.BlockEntry collision(GuardStore store, String address) throws Exception {
        var error = assertThrows(BlockedException.class, () -> store.checkAndRecord(SERVER, BOB, "Bob", Set.of(address)));
        return store.recordBlock(SERVER, BOB, "Bob", error, false);
    }
    private void seedCollision() throws Exception {
        store().setLock(OTHER, ALICE);
        store().checkAndRecord(OTHER, ALICE, "Alice", Set.of("8.8.8.8", "1.1.1.1"));
        store().setLock(SERVER, BOB);
    }

    @Test void hourlyExemptionSurvivesRestartAndExpiresAtExactDeadline() throws Exception {
        store().setLock(SERVER, ALICE);
        var block = uuidBlock(store(), SERVER, BOB);
        store().grantExemption(block.id(), false);
        assertDoesNotThrow(() -> store(NOW + 3_599_999).requireLock(SERVER, BOB));
        assertThrows(BlockedException.class, () -> store(NOW + 3_600_000).requireLock(SERVER, BOB));
        assertTrue(store(NOW + 3_600_000).snapshot().exemptions().isEmpty());
    }

    @Test void permanentExemptionPersistsAndCanBeRevoked() throws Exception {
        var block = uuidBlock(store(), SERVER, ALICE);
        store().grantExemption(block.id(), true);
        var later = store(NOW + 31_536_000_000L);
        assertDoesNotThrow(() -> later.requireLock(SERVER, ALICE));
        later.revokeExemption(later.snapshot().exemptions().getFirst().id());
        assertThrows(BlockedException.class, () -> later.requireLock(SERVER, ALICE));
    }

    @Test void uuidExemptionCannotAllowAnotherAccountOrServer() throws Exception {
        store().setLock(SERVER, ALICE);
        store().setLock(OTHER, ALICE);
        store().grantExemption(uuidBlock(store(), SERVER, BOB).id(), true);
        assertDoesNotThrow(() -> store().requireLock(SERVER, BOB));
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, CAROL));
        assertThrows(BlockedException.class, () -> store().requireLock(OTHER, BOB));
    }

    @Test void reassigningLockRevokesItsExemptionAndStaleBlockCannotRestoreIt() throws Exception {
        store().setLock(SERVER, ALICE);
        var block = uuidBlock(store(), SERVER, BOB);
        store().grantExemption(block.id(), true);
        store().setLock(SERVER, CAROL);
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, BOB));
        assertThrows(BlockedException.class, () -> store().grantExemption(block.id(), true));
        assertTrue(store().snapshot().exemptions().isEmpty());
    }

    @Test void ipExemptionIsScopedAndSharedHistoryRemainsReadableAfterExpiry() throws Exception {
        seedCollision();
        store().grantExemption(collision(store(), "8.8.8.8").id(), false);
        store().checkAndRecord(SERVER, BOB, "Bob", Set.of("8.8.8.8"));
        assertEquals(1, store().addressCount(BOB));
        assertEquals(2, store().addressCount(ALICE));
        assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, BOB, "Bob", Set.of("1.1.1.1")));
        store().setLock("third.org:25565", BOB);
        assertThrows(BlockedException.class, () -> store().checkAndRecord("third.org:25565", BOB, "Bob", Set.of("8.8.8.8")));
        assertThrows(BlockedException.class, () -> store(NOW + 3_600_000).checkAndRecord(SERVER, BOB, "Bob", Set.of("8.8.8.8")));
        assertEquals(1, store(NOW + 3_600_000).addressCount(BOB));
    }

    @Test void uuidExemptionDoesNotBypassSameIp() throws Exception {
        store().setLock(SERVER, ALICE);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8"));
        store().grantExemption(uuidBlock(store(), SERVER, BOB).id(), true);
        assertEquals(GuardRule.SAME_IP, assertThrows(BlockedException.class,
                () -> store().checkAndRecord(SERVER, BOB, "Bob", Set.of("8.8.8.8"))).rule);
    }

    @Test void lookupFailureExemptionDoesNotSkipAvailableIpOrUuidChecks() throws Exception {
        seedCollision();
        assertFalse(store().allowLookupFailure(SERVER, BOB, "Bob"));
        var failure = new BlockedException(GuardRule.IP_LOOKUP, "Unavailable", null, Set.of(), null);
        var block = store().recordBlock(SERVER, BOB, "Bob", failure, false);
        store().grantExemption(block.id(), false);
        assertTrue(store().allowLookupFailure(SERVER, BOB, "Bob"));
        assertEquals(0, store().addressCount(BOB));
        assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, BOB, "Bob", Set.of("8.8.8.8")));
        assertThrows(BlockedException.class, () -> store().allowLookupFailure(SERVER, CAROL, "Carol"));
        assertFalse(store(NOW + 3_600_000).allowLookupFailure(SERVER, BOB, "Bob"));
    }

    @Test void recentBlocksDeduplicateAndStayBoundedNewestFirst() throws Exception {
        var first = uuidBlock(store(), SERVER, BOB);
        var repeated = uuidBlock(store(NOW + 1), SERVER, BOB);
        assertEquals(first.id(), repeated.id());
        assertEquals(2, repeated.count());
        assertEquals(NOW + 1, repeated.time());
        for (int i = 0; i < 105; i++) uuidBlock(store(NOW + 2 + i), "server" + i + ".org:25565", BOB);
        var blocks = store().snapshot().blocks();
        assertEquals(100, blocks.size());
        assertEquals("server104.org:25565", blocks.getFirst().server());
    }

    @Test void managementEditsLabelsAndLocksWithoutDiscardingIpHistory() throws Exception {
        store().setLock(SERVER, ALICE);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8"));
        store().saveAlt(ALICE, "Builder alt");
        store().checkAndRecord(SERVER, ALICE, "RenamedAlice", Set.of("1.1.1.1"));
        store().editLock(SERVER, OTHER, ALICE);
        assertNull(store().lockedAccount(SERVER));
        assertEquals(ALICE, store().lockedAccount(OTHER));
        var alt = store().snapshot().alts().getFirst();
        assertEquals("Builder alt", alt.displayName());
        assertEquals("RenamedAlice", alt.name());
        assertEquals(2, alt.addresses().size());
        store().removeLock(OTHER);
        assertEquals(2, store().addressCount(ALICE));
        assertThrows(BlockedException.class, () -> store().requireLock(OTHER, ALICE));
    }

    @Test void removingAltRemovesOnlyItsHistoryAndAssociatedManagementData() throws Exception {
        seedCollision();
        store().grantExemption(collision(store(), "8.8.8.8").id(), true);
        store().removeAlt(ALICE);
        assertNull(store().lockedAccount(OTHER));
        assertEquals(BOB, store().lockedAccount(SERVER));
        assertEquals(0, store().addressCount(ALICE));
        assertEquals(1, store().snapshot().alts().size());
        store().checkAndRecord(SERVER, BOB, "Bob", Set.of("1.1.1.1"));
        store().removeAlt(BOB);
        assertTrue(store().snapshot().blocks().isEmpty());
        assertTrue(store().snapshot().exemptions().isEmpty());
        assertTrue(store().snapshot().locks().isEmpty());
    }

    @Test void removingLockRevokesItsExemptions() throws Exception {
        store().setLock(SERVER, ALICE);
        store().grantExemption(uuidBlock(store(), SERVER, BOB).id(), true);
        store().removeLock(SERVER);
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, BOB));
        assertTrue(store().snapshot().exemptions().isEmpty());
    }

    @Test void legacyHistoryMigratesWithoutLosingRecords() throws Exception {
        Files.writeString(store().path(), """
                {"version":1,"servers":{"example.org:25565":"%s"},
                 "accounts":{"%s":{"name":"Alice","addresses":["8.8.8.8"]}}}
                """.formatted(ALICE, ALICE));
        assertEquals(1, store().addressCount(ALICE));
        store().saveAlt(ALICE, "Builder alt");
        assertEquals(2, JsonParser.parseString(Files.readString(store().path())).getAsJsonObject().get("version").getAsInt());
        assertEquals(ALICE, store().lockedAccount(SERVER));
        assertEquals(1, store().addressCount(ALICE));
    }

    @Test void missingExemptionExpiryCannotTurnIntoForever() throws Exception {
        store().grantExemption(uuidBlock(store(), SERVER, ALICE).id(), false);
        var json = JsonParser.parseString(Files.readString(store().path())).getAsJsonObject();
        json.getAsJsonArray("exemptions").get(0).getAsJsonObject().remove("expiresAt");
        Files.writeString(store().path(), json.toString());
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, ALICE));
    }

    @Test void storageAndIdentityErrorsCannotBeExempted() throws Exception {
        var block = store().recordBlock(SERVER, ALICE, "Alice", new BlockedException("Storage failed"), false);
        assertThrows(BlockedException.class, () -> store().grantExemption(block.id(), true));
    }
}
