package dev.goodrich.althalt.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardStoreTest {
    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SERVER = "example.org:25565";
    private static final String OTHER = "another.org:25565";
    @TempDir Path directory;

    private GuardStore store() { return new GuardStore(directory); }

    @Test void unconfiguredServerIsBlockedWithoutCreatingHistory() {
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, ALICE));
        assertFalse(Files.exists(store().path()));
    }

    @Test void uuidMismatchIsBlocked() throws Exception {
        store().setLock(SERVER, ALICE);
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, BOB));
        assertDoesNotThrow(() -> store().requireLock(SERVER, ALICE));
    }

    @Test void historySurvivesRestartAndAppliesAcrossServers() throws Exception {
        store().setLock(SERVER, ALICE);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8"));
        store().setLock(OTHER, BOB);
        BlockedException failure = assertThrows(BlockedException.class,
                () -> store().checkAndRecord(OTHER, BOB, "Bob", Set.of("8.8.8.8")));
        assertTrue(failure.getMessage().startsWith("Same-IP blocked"));
        assertEquals(0, store().addressCount(BOB));
    }

    @Test void sameAccountCanReuseIpAndAccumulatesNewIps() throws Exception {
        store().setLock(SERVER, ALICE);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8"));
        store().checkAndRecord(SERVER, ALICE, "AliceRenamed", Set.of("8.8.8.8", "1.1.1.1"));
        assertEquals(2, store().addressCount(ALICE));
        store().setLock(SERVER, BOB);
        assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, BOB, "Bob", Set.of("8.8.8.8")));
    }

    @Test void differentAccountsCanUseDifferentIps() throws Exception {
        store().setLock(SERVER, ALICE);
        store().setLock(OTHER, BOB);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8"));
        store().checkAndRecord(OTHER, BOB, "Bob", Set.of("1.1.1.1"));
        assertEquals(1, store().addressCount(BOB));
    }

    @Test void ipv6TextAliasesCannotBypassCollisionCheck() throws Exception {
        store().setLock(SERVER, ALICE);
        store().setLock(OTHER, BOB);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("2606:4700:4700::1111"));
        assertThrows(BlockedException.class, () -> store().checkAndRecord(OTHER, BOB, "Bob",
                Set.of("2606:4700:4700:0:0:0:0:1111")));
    }

    @Test void oneConflictingAddressRejectsEntireAttempt() throws Exception {
        store().setLock(SERVER, ALICE);
        store().setLock(OTHER, BOB);
        store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8"));
        String before = Files.readString(store().path());
        assertThrows(BlockedException.class, () -> store().checkAndRecord(OTHER, BOB, "Bob", Set.of("8.8.8.8", "1.1.1.1")));
        assertEquals(before, Files.readString(store().path()));
    }

    @Test void lockIsRecheckedWhenHistoryIsReserved() throws Exception {
        store().setLock(SERVER, ALICE);
        store().requireLock(SERVER, ALICE);
        store().setLock(SERVER, BOB);
        assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8")));
        assertEquals(0, store().addressCount(ALICE));
    }

    @Test void malformedHistoryIsPreservedAndFailsClosed() throws Exception {
        Files.writeString(store().path(), "{broken");
        assertThrows(BlockedException.class, () -> store().setLock(SERVER, ALICE));
        assertThrows(BlockedException.class, () -> store().requireLock(SERVER, ALICE));
        assertEquals("{broken", Files.readString(store().path()));
    }

    @Test void incompleteOrUnknownSchemaFailsClosed() throws Exception {
        for (String value : new String[]{"{}", "null", "[]", "{\"version\":2,\"servers\":{},\"accounts\":{}}",
                "{version:1,servers:{},accounts:{}}", "{\"version\":1.5,\"servers\":{},\"accounts\":{}}",
                "{\"version\":1,\"servers\":{},\"accounts\":null}"}) {
            Files.writeString(store().path(), value);
            assertThrows(BlockedException.class, () -> store().setLock(SERVER, ALICE));
            assertEquals(value, Files.readString(store().path()));
        }
    }

    @Test void invalidOrEmptyLookupCannotCreateHistory() throws Exception {
        store().setLock(SERVER, ALICE);
        assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, ALICE, "Alice", Set.of()));
        assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("example.org")));
        assertEquals(0, store().addressCount(ALICE));
    }

    @Test void missingAccountUuidIsBlocked() {
        assertThrows(BlockedException.class, () -> store().setLock(SERVER, null));
        assertThrows(BlockedException.class, () -> store().setLock(SERVER, new UUID(0, 0)));
    }

    @Test void lockedHistoryBlocksOtherClientInsteadOfOverwritingIt() throws Exception {
        store().setLock(SERVER, ALICE);
        try (var channel = FileChannel.open(directory.resolve("state.lock"), StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(BlockedException.class, () -> store().checkAndRecord(SERVER, ALICE, "Alice", Set.of("8.8.8.8")));
        }
        assertEquals(0, store().addressCount(ALICE));
    }

    @Test void concurrentClaimsNeverAssignOneIpToTwoAccounts() throws Exception {
        store().setLock(SERVER, ALICE);
        store().setLock(OTHER, BOB);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> alice = () -> claim(SERVER, ALICE);
            Callable<Boolean> bob = () -> claim(OTHER, BOB);
            var results = executor.invokeAll(java.util.List.of(alice, bob));
            assertEquals(1, results.stream().filter(result -> {
                try { return result.get(); } catch (Exception error) { throw new RuntimeException(error); }
            }).count());
        }
        assertEquals(1, store().addressCount(ALICE) + store().addressCount(BOB));
    }

    private boolean claim(String server, UUID account) {
        try {
            store().checkAndRecord(server, account, account.toString(), Set.of("8.8.8.8"));
            return true;
        } catch (BlockedException ignored) {
            return false;
        }
    }

    @Test void unwriteableHistoryLocationBlocks() throws Exception {
        Path file = directory.resolve("file");
        Files.writeString(file, "not a directory");
        assertThrows(BlockedException.class, () -> new GuardStore(file).setLock(SERVER, ALICE));
    }
}
