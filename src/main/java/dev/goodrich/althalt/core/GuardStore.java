package dev.goodrich.althalt.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared by launcher profiles. Every read-modify-write holds the same cross-process lock. */
public final class GuardStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT).create();
    private static final int MAX_BLOCKS = 100;
    private final Path directory;
    private final Clock clock;

    public GuardStore(Path directory) { this(directory, Clock.systemUTC()); }
    public GuardStore(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }
    public Path path() { return directory.resolve("state.json"); }

    public record BlockEntry(String id, long time, String server, UUID account, String name, GuardRule rule,
                             String detail, String expectedAccount, Set<String> addresses, boolean ping, int count) {}
    public record Exemption(String id, String server, UUID account, GuardRule rule, String expectedAccount,
                            Set<String> addresses, long expiresAt) {
        public boolean active(long now) { return expiresAt == 0 || now < expiresAt; }
    }
    public record Alt(UUID uuid, String name, String label, Set<String> addresses, List<String> servers) {
        public String displayName() { return label == null || label.isBlank() ? name : label; }
    }
    public record Snapshot(List<BlockEntry> blocks, List<Alt> alts, Map<String, UUID> locks, List<Exemption> exemptions) {}

    public Snapshot snapshot() throws BlockedException {
        return transaction(false, state -> {
            Map<String, UUID> locks = new LinkedHashMap<>();
            state.servers.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> locks.put(entry.getKey(), UUID.fromString(entry.getValue())));
            Set<String> ids = new LinkedHashSet<>(state.accounts.keySet());
            ids.addAll(state.servers.values());
            List<Alt> alts = new ArrayList<>();
            for (String id : ids) {
                Account entry = state.accounts.get(id);
                alts.add(new Alt(UUID.fromString(id), entry == null ? id : entry.name, entry == null ? null : entry.label,
                        entry == null ? Set.of() : Set.copyOf(entry.addresses),
                        locks.entrySet().stream().filter(lock -> lock.getValue().toString().equals(id)).map(Map.Entry::getKey).toList()));
            }
            alts.sort(Comparator.comparing(Alt::displayName, String.CASE_INSENSITIVE_ORDER).thenComparing(Alt::uuid));
            return new Snapshot(List.copyOf(state.blocks), List.copyOf(alts), java.util.Collections.unmodifiableMap(locks),
                    state.exemptions.stream().filter(exemption -> exemption.active(clock.millis())).toList());
        });
    }

    public UUID lockedAccount(String server) throws BlockedException {
        return transaction(false, state -> {
            String account = state.servers.get(server);
            return account == null ? null : UUID.fromString(account);
        });
    }

    public int addressCount(UUID account) throws BlockedException {
        return transaction(false, state -> {
            Account entry = state.accounts.get(account.toString());
            return entry == null ? 0 : entry.addresses.size();
        });
    }

    public void setLock(String server, UUID account) throws BlockedException { editLock(null, server, account); }

    public void editLock(String original, String server, UUID account) throws BlockedException {
        requireAccount(account);
        requireServer(server);
        transaction(true, state -> {
            if (original != null && !original.equals(server) && state.servers.containsKey(server)) {
                throw new BlockedException("That destination already has a lock. Edit it separately.");
            }
            if (original != null && !original.equals(server)) {
                state.servers.remove(original);
                state.exemptions.removeIf(exemption -> exemption.server().equals(original));
            }
            // Reassignment also revokes exceptions for an earlier UUID lock.
            if (!Objects.equals(state.servers.get(server), account.toString())) {
                state.exemptions.removeIf(exemption -> exemption.server().equals(server) && exemption.rule() == GuardRule.UUID_LOCK);
            }
            state.servers.put(server, account.toString());
            remember(state, account, null);
            return null;
        });
    }

    public void removeLock(String server) throws BlockedException {
        transaction(true, state -> {
            state.servers.remove(server);
            state.exemptions.removeIf(exemption -> exemption.server().equals(server));
            return null;
        });
    }

    public void saveAlt(UUID account, String label) throws BlockedException {
        requireAccount(account);
        String value = label == null ? "" : label.strip();
        if (value.length() > 64) throw new BlockedException("Account labels can be at most 64 characters.");
        transaction(true, state -> { remember(state, account, null).label = value; return null; });
    }

    /** Explicit management action: erase this account's history, locks, exemptions and recent blocks. */
    public void removeAlt(UUID account) throws BlockedException {
        requireAccount(account);
        transaction(true, state -> {
            Set<String> removedServers = new LinkedHashSet<>();
            state.servers.forEach((server, id) -> { if (id.equals(account.toString())) removedServers.add(server); });
            removedServers.forEach(state.servers::remove);
            state.accounts.remove(account.toString());
            state.exemptions.removeIf(exemption -> exemption.account().equals(account)
                    || Objects.equals(exemption.expectedAccount(), account.toString()) || removedServers.contains(exemption.server()));
            state.blocks.removeIf(block -> block.account().equals(account));
            return null;
        });
    }

    public void requireLock(String server, UUID account) throws BlockedException {
        requireAccount(account);
        transaction(false, state -> { checkLock(state, server, account); return null; });
    }

    public void checkAndRecord(String server, UUID account, String name, Set<String> addresses) throws BlockedException {
        requireAccount(account);
        if (addresses == null || addresses.isEmpty()) throw new BlockedException("No public IP address was verified.");
        Set<String> normalized = new LinkedHashSet<>();
        try {
            for (String address : addresses) normalized.add(IpAddresses.normalize(address));
        } catch (IllegalArgumentException error) {
            throw new BlockedException("The IP lookup returned an invalid address.", error);
        }
        transaction(true, state -> {
            checkLock(state, server, account);
            Set<String> collisions = new LinkedHashSet<>();
            Set<String> owners = new LinkedHashSet<>();
            for (var entry : state.accounts.entrySet()) {
                if (!entry.getKey().equals(account.toString())) {
                    for (String address : normalized) {
                        if (entry.getValue().addresses.contains(address)
                                && !exempt(state, server, account, GuardRule.SAME_IP, null, address)) {
                            collisions.add(address);
                            owners.add(displayName(entry.getValue()) + " (" + entry.getKey() + ")");
                        }
                    }
                }
            }
            if (!collisions.isEmpty()) {
                throw new BlockedException(GuardRule.SAME_IP, "Same-IP blocked: this connection would reuse an IP recorded for "
                        + String.join(", ", owners) + ". Change your network or review this block in AltHalt.", null, collisions, null);
            }
            remember(state, account, name).addresses.addAll(normalized);
            return null;
        });
    }

    /** Only used after a lookup has failed, never to bypass a successful lookup's collision check. */
    public boolean allowLookupFailure(String server, UUID account, String name) throws BlockedException {
        requireAccount(account);
        return transaction(true, state -> {
            checkLock(state, server, account);
            if (!exempt(state, server, account, GuardRule.IP_LOOKUP, null, null)) return false;
            remember(state, account, name);
            return true;
        });
    }

    public BlockEntry recordBlock(String server, UUID account, String name, BlockedException failure, boolean ping) throws BlockedException {
        requireAccount(account);
        requireServer(server);
        return transaction(true, state -> {
            BlockEntry previous = state.blocks.stream().filter(block -> block.server().equals(server) && block.account().equals(account)
                    && block.rule() == failure.rule && Objects.equals(block.expectedAccount(), failure.expectedAccount)
                    && block.addresses().equals(failure.addresses) && block.ping() == ping).findFirst().orElse(null);
            BlockEntry event = new BlockEntry(previous == null ? UUID.randomUUID().toString() : previous.id(), clock.millis(), server,
                    account, name == null ? account.toString() : name, failure.rule, failure.getMessage(), failure.expectedAccount,
                    failure.addresses, ping, previous == null ? 1 : Math.min(Integer.MAX_VALUE - 1, previous.count()) + 1);
            if (previous != null) state.blocks.remove(previous);
            state.blocks.addFirst(event);
            if (state.blocks.size() > MAX_BLOCKS) state.blocks.removeLast();
            remember(state, account, name);
            return event;
        });
    }

    public void grantExemption(String blockId, boolean forever) throws BlockedException {
        transaction(true, state -> {
            BlockEntry block = state.blocks.stream().filter(entry -> entry.id().equals(blockId)).findFirst()
                    .orElseThrow(() -> new BlockedException("This block is no longer in recent history."));
            if (!block.rule().exemptible) throw new BlockedException("Storage and account-identity errors cannot be exempted.");
            if (block.rule() == GuardRule.UUID_LOCK && !Objects.equals(state.servers.get(block.server()), block.expectedAccount())) {
                throw new BlockedException("This server's lock has changed. Retry the connection before exempting it.");
            }
            state.exemptions.removeIf(entry -> entry.server().equals(block.server()) && entry.account().equals(block.account())
                    && entry.rule() == block.rule() && Objects.equals(entry.expectedAccount(), block.expectedAccount())
                    && entry.addresses().equals(block.addresses()));
            state.exemptions.add(new Exemption(UUID.randomUUID().toString(), block.server(), block.account(), block.rule(),
                    block.expectedAccount(), block.addresses(), forever ? 0 : clock.millis() + Duration.ofHours(1).toMillis()));
            return null;
        });
    }

    public void revokeExemption(String id) throws BlockedException {
        transaction(true, state -> { state.exemptions.removeIf(entry -> entry.id().equals(id)); return null; });
    }

    private static Account remember(State state, UUID account, String name) {
        Account entry = state.accounts.computeIfAbsent(account.toString(), ignored -> new Account());
        if (name != null && !name.isBlank()) entry.name = name;
        if (entry.name == null) entry.name = account.toString();
        return entry;
    }

    private static String displayName(Account account) {
        return account.label == null || account.label.isBlank() ? account.name : account.label;
    }

    private boolean exempt(State state, String server, UUID account, GuardRule rule, String expected, String address) {
        return state.exemptions.stream().anyMatch(entry -> entry.active(clock.millis()) && entry.server().equals(server)
                && entry.account().equals(account) && entry.rule() == rule && Objects.equals(entry.expectedAccount(), expected)
                && (address == null || entry.addresses().contains(address)));
    }

    private static void requireAccount(UUID account) throws BlockedException {
        if (account == null || account.equals(new UUID(0, 0))) throw new BlockedException("The current account has no valid UUID.");
    }

    private static void requireServer(String server) throws BlockedException {
        if (!ServerKey.of(server).equals(server)) throw new BlockedException("Invalid server address.");
    }

    private void checkLock(State state, String server, UUID account) throws BlockedException {
        String allowed = state.servers.get(server);
        if (Objects.equals(allowed, account.toString()) || exempt(state, server, account, GuardRule.UUID_LOCK, allowed, null)) return;
        String detail = allowed == null ? "This server has no UUID lock. Assign its allowed account in AltHalt first."
                : "UUID Locked: this server only allows " + allowed + ". Your current account is " + account + ".";
        throw new BlockedException(GuardRule.UUID_LOCK, detail, allowed, Set.of(), null);
    }

    private synchronized <T> T transaction(boolean write, Operation<T> operation) throws BlockedException {
        try {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(directory.resolve("state.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new BlockedException("AltHalt history is in use by another client. Try again.");
                State state = read();
                T result = operation.run(state);
                if (write) {
                    state.exemptions.removeIf(entry -> !entry.active(clock.millis()));
                    save(state);
                }
                return result;
            }
        } catch (IOException | RuntimeException error) {
            throw new BlockedException("AltHalt could not safely read or save its history. Check " + path(), error);
        }
    }

    private State read() throws IOException {
        if (!Files.exists(path())) return new State();
        var json = GSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), JsonObject.class);
        if (json == null || !json.has("version") || !json.has("servers") || !json.has("accounts")) throw new IOException("Missing history fields.");
        String version = json.get("version").toString();
        if (!version.equals("1") && !version.equals("2")) throw new IOException("Unsupported history version.");
        if (version.equals("2") && (!json.has("blocks") || !json.has("exemptions"))) throw new IOException("Missing management history.");
        if (version.equals("2")) {
            for (var value : json.getAsJsonArray("exemptions")) {
                if (!value.getAsJsonObject().has("expiresAt")) throw new IOException("Missing exemption expiry.");
            }
        }
        State state = GSON.fromJson(json, State.class);
        if (state.servers == null || state.accounts == null || state.blocks == null || state.exemptions == null) throw new IOException("Invalid history schema.");
        for (var entry : state.servers.entrySet()) {
            validateServer(entry.getKey());
            validateUuid(entry.getValue());
        }
        Map<String, String> owners = new LinkedHashMap<>();
        for (var entry : state.accounts.entrySet()) {
            validateUuid(entry.getKey());
            Account account = entry.getValue();
            if (account == null || account.name == null || account.addresses == null || (version.equals("1") && account.addresses.isEmpty())) {
                throw new IOException("Invalid account history.");
            }
            for (String address : account.addresses) {
                if (!IpAddresses.normalize(address).equals(address)) throw new IOException("Non-canonical IP history.");
                if (owners.putIfAbsent(address, entry.getKey()) != null && version.equals("1")) throw new IOException("Conflicting legacy IP history.");
            }
        }
        if (state.blocks.size() > MAX_BLOCKS) throw new IOException("Too many recent blocks.");
        Set<String> ids = new LinkedHashSet<>();
        for (BlockEntry block : state.blocks) {
            validateScope(block.server(), block.account(), block.rule(), block.expectedAccount(), block.addresses());
            validateUuid(block.id());
            if (!ids.add(block.id()) || block.name() == null || block.detail() == null || block.time() < 0 || block.count() < 1) throw new IOException("Invalid block.");
        }
        ids.clear();
        for (Exemption entry : state.exemptions) {
            validateScope(entry.server(), entry.account(), entry.rule(), entry.expectedAccount(), entry.addresses());
            validateUuid(entry.id());
            if (!ids.add(entry.id()) || !entry.rule().exemptible || entry.expiresAt() < 0) throw new IOException("Invalid exemption.");
        }
        state.version = 2;
        return state;
    }

    private static void validateScope(String server, UUID account, GuardRule rule, String expected, Set<String> addresses) throws IOException {
        validateServer(server);
        validateUuid(account.toString());
        if (rule == null || addresses == null) throw new IOException("Incomplete scope.");
        if (expected != null) validateUuid(expected);
        if (rule != GuardRule.UUID_LOCK && expected != null) throw new IOException("Unexpected UUID scope.");
        if ((rule == GuardRule.SAME_IP) == addresses.isEmpty()) throw new IOException("Invalid address scope.");
        for (String address : addresses) if (!IpAddresses.normalize(address).equals(address)) throw new IOException("Invalid address scope.");
    }

    private static void validateServer(String server) throws IOException {
        if (!ServerKey.of(server).equals(server)) throw new IOException("Invalid server key.");
    }

    private static void validateUuid(String value) throws IOException {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value) || uuid.equals(new UUID(0, 0))) throw new IOException("Invalid UUID.");
    }

    private void save(State state) throws IOException {
        Path temporary = Files.createTempFile(directory, "state-", ".tmp");
        try {
            byte[] bytes = GSON.toJson(state).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private interface Operation<T> { T run(State state) throws BlockedException; }
    private static final class State {
        int version = 2;
        Map<String, String> servers = new LinkedHashMap<>();
        Map<String, Account> accounts = new LinkedHashMap<>();
        List<BlockEntry> blocks = new ArrayList<>();
        List<Exemption> exemptions = new ArrayList<>();
    }
    private static final class Account {
        String name;
        String label;
        Set<String> addresses = new LinkedHashSet<>();
    }
}
