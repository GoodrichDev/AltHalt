package dev.goodrich.althalt.smoke;

import dev.goodrich.althalt.AltHaltClient;
import dev.goodrich.althalt.fixture.LookupFixture;
import dev.goodrich.althalt.fixture.StatusPingFixture;
import dev.goodrich.althalt.core.GuardStore;
import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.GuardRule;
import dev.goodrich.althalt.core.ServerKey;
import dev.goodrich.althalt.ui.CheckingScreen;
import dev.goodrich.althalt.ui.ServerLockScreen;
import dev.goodrich.althalt.ui.ManagerScreen;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uses a loopback listener to prove blocked attempts never reach the destination socket. */
@Mixin(Minecraft.class)
public abstract class ClientSmokeMixin {
    @Unique private int althalt$ticks;
    @Unique private int althalt$step;
    @Unique private ServerSocket althalt$listener;
    @Unique private Path althalt$state;
    @Unique private ServerData althalt$server;
    @Unique private JoinMultiplayerScreen althalt$menu;
    @Unique private ServerStatusPinger althalt$pinger;
    @Unique private java.util.concurrent.CompletableFuture<Void> althalt$pingExchange;
    @Unique private final java.util.concurrent.atomic.AtomicInteger althalt$pongs = new java.util.concurrent.atomic.AtomicInteger();

    @Inject(method = "tick", at = @At("TAIL"))
    private void althalt$smoke(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.gui.overlay() != null || client.gui.screen() == null) return;
        if (althalt$pinger != null) althalt$pinger.tick();
        if (++althalt$ticks % 25 != 0) return;
        try {
            switch (althalt$step++) {
                case 0 -> {
                    althalt$state = Files.createTempDirectory(Path.of("."), "history-");
                    var field = AltHaltClient.class.getDeclaredField("store");
                    field.setAccessible(true);
                    field.set(null, new GuardStore(althalt$state));
                    althalt$listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
                    althalt$listener.setSoTimeout(150);
                    althalt$server = new ServerData("AltHalt smoke", "localhost:" + althalt$listener.getLocalPort(), ServerData.Type.OTHER);
                    althalt$menu = new JoinMultiplayerScreen(new TitleScreen());
                    client.gui.setScreen(althalt$menu);
                }
                case 1 -> {
                    althalt$button(client.gui.screen(), "AltHalt").onPress(null);
                    althalt$assert(client.gui.screen() instanceof ManagerScreen, "Multiplayer management button");
                    althalt$button(client.gui.screen(), "Add lock").onPress(null);
                    althalt$assert(client.gui.screen() instanceof ServerLockScreen, "Management opens lock setup");
                    client.gui.setScreen(new ServerLockScreen(althalt$menu, althalt$server.ip));
                    althalt$button(client.gui.screen(), "Use current account").onPress(null);
                    althalt$button(client.gui.screen(), "Save UUID lock").onPress(null);
                    althalt$assert(client.getUser().getProfileId().equals(AltHaltClient.store().lockedAccount(ServerKey.of(althalt$server.ip))), "Save current UUID");
                }
                case 2 -> althalt$capture(client, "settings.png");
                case 3 -> {
                    AltHaltClient.store().setLock(ServerKey.of(althalt$server.ip), UUID.fromString("11111111-1111-4111-8111-111111111111"));
                    ConnectScreen.startConnecting(althalt$menu, client, ServerAddress.parseString(althalt$server.ip), althalt$server, false, null);
                }
                case 4 -> {
                    althalt$assert(client.gui.screen() instanceof DisconnectedScreen, "UUID mismatch blocked");
                    althalt$noConnection();
                    althalt$capture(client, "uuid-blocked.png");
                }
                case 5 -> {
                    // Destination differs from ServerData, as it does during server transfers.
                    ServerAddress destination = new ServerAddress("localhost", althalt$listener.getLocalPort() + 1);
                    ConnectScreen.startConnecting(althalt$menu, client, destination, althalt$server, true, null);
                }
                case 6 -> {
                    althalt$assert(client.gui.screen() instanceof DisconnectedScreen, "Unconfigured Quick Play destination blocked");
                    althalt$noConnection();
                    althalt$capture(client, "unconfigured-blocked.png");
                    new ServerStatusPinger().pingServer(althalt$server, () -> {}, () -> {}, EventLoopGroupHolder.remote(false));
                }
                case 7 -> {
                    althalt$noConnection();
                    althalt$assert(althalt$server.motd.getString().contains("AltHalt"), "Background ping suppressed");
                    AltHaltClient.store().setLock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId());
                    ConnectScreen.startConnecting(althalt$menu, client, ServerAddress.parseString(althalt$server.ip), althalt$server, false, null);
                    althalt$assert(client.gui.screen() instanceof CheckingScreen, "IP check screen opened");
                    client.gui.screen().onClose();
                }
                case 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19 -> althalt$noConnection();
                case 20 -> {
                    althalt$noConnection();
                    althalt$assert(client.gui.screen() == althalt$menu, "Cancelled check cannot reconnect");
                    althalt$assert(AltHaltClient.store().addressCount(client.getUser().getProfileId()) == 0, "Cancellation did not reserve an IP");
                    LookupFixture.fail = true;
                    ConnectScreen.startConnecting(althalt$menu, client, ServerAddress.parseString(althalt$server.ip), althalt$server, false, null);
                }
                case 21 -> {
                    althalt$assert(client.gui.screen() instanceof DisconnectedScreen, "Lookup failure blocked");
                    althalt$noConnection();
                    LookupFixture.fail = false;
                    UUID other = UUID.fromString("11111111-1111-4111-8111-111111111111");
                    AltHaltClient.store().setLock("other.example:25565", other);
                    AltHaltClient.store().checkAndRecord("other.example:25565", other, "Other account", java.util.Set.of("8.8.8.8"));
                    ConnectScreen.startConnecting(althalt$menu, client, ServerAddress.parseString(althalt$server.ip), althalt$server, false, null);
                }
                case 22 -> {
                    althalt$assert(client.gui.screen() instanceof DisconnectedScreen, "Same-IP collision blocked");
                    althalt$noConnection();
                    althalt$capture(client, "same-ip-blocked.png");
                    // Reset only the disposable test store to exercise an approved connection.
                    Files.delete(althalt$state.resolve("state.json"));
                    AltHaltClient.store().setLock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId());
                    ConnectScreen.startConnecting(althalt$menu, client, ServerAddress.parseString(althalt$server.ip), althalt$server, false, null);
                }
                case 23 -> {
                    try (var socket = althalt$listener.accept()) {
                        socket.setSoTimeout(1500);
                        althalt$assert(socket.getInputStream().read() >= 0, "Approved connection sends handshake");
                    }
                    althalt$assert(AltHaltClient.store().addressCount(client.getUser().getProfileId()) == 1, "IP saved before connection");
                }
                case 24 -> {
                    althalt$pinger = new ServerStatusPinger();
                    althalt$pingExchange = StatusPingFixture.serveOnce(althalt$listener);
                    // Background status should work even when a login IP lookup would fail.
                    LookupFixture.fail = true;
                    althalt$pinger.pingServer(althalt$server, () -> {}, althalt$pongs::incrementAndGet, EventLoopGroupHolder.remote(false));
                }
                case 25 -> {
                    althalt$assert(althalt$pingExchange.isDone(), "Matching UUID completed status exchange");
                    althalt$pingExchange.join();
                    althalt$assert(althalt$pongs.get() == 1, "Matching UUID received pong");
                    althalt$assert(althalt$server.motd.getString().equals("AltHalt ping test"), "Status response populated server entry");
                    althalt$pingExchange = StatusPingFixture.serveOnce(althalt$listener);
                    althalt$pinger.pingServer(althalt$server, () -> {}, althalt$pongs::incrementAndGet, EventLoopGroupHolder.remote(false));
                }
                case 26 -> {
                    althalt$assert(althalt$pingExchange.isDone(), "Repeated refresh completed status exchange");
                    althalt$pingExchange.join();
                    althalt$assert(althalt$pongs.get() == 2, "Repeated refresh received pong");
                    Files.delete(althalt$state.resolve("state.json"));
                    althalt$pinger.pingServer(althalt$server, () -> {}, () -> {}, EventLoopGroupHolder.remote(false));
                }
                case 27 -> {
                    althalt$noConnection();
                    althalt$assert(althalt$server.motd.getString().contains("AltHalt: ping blocked"), "Unconfigured server ping blocked");
                    Files.writeString(althalt$state.resolve("state.json"), "{broken");
                    althalt$pinger.pingServer(althalt$server, () -> {}, () -> {}, EventLoopGroupHolder.remote(false));
                }
                case 28 -> {
                    althalt$noConnection();
                    althalt$assert(althalt$server.motd.getString().contains("AltHalt: ping blocked"), "Corrupt history ping blocked");
                    Files.delete(althalt$state.resolve("state.json"));
                    UUID owner = UUID.fromString("11111111-1111-4111-8111-111111111111");
                    AltHaltClient.store().setLock("other.example:25565", owner);
                    AltHaltClient.store().checkAndRecord("other.example:25565", owner, "Other account", java.util.Set.of("8.8.8.8"));
                    AltHaltClient.store().setLock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId());
                    try {
                        AltHaltClient.store().checkAndRecord(ServerKey.of(althalt$server.ip), client.getUser().getProfileId(), client.getUser().getName(), java.util.Set.of("8.8.8.8"));
                        throw new AssertionError("Expected fixture IP collision");
                    } catch (BlockedException expected) {
                        AltHaltClient.store().recordBlock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId(), client.getUser().getName(), expected, false);
                    }
                    client.gui.setScreen(new ManagerScreen(althalt$menu, althalt$server.ip));
                }
                case 29 -> {
                    althalt$capture(client, "management-blocks.png");
                    althalt$buttonContaining(client.gui.screen(), "Same-IP").onPress(null);
                }
                case 30 -> {
                    althalt$capture(client, "block-details.png");
                    althalt$button(client.gui.screen(), "Exempt 1 hour").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().exemptions().getFirst().expiresAt() > System.currentTimeMillis(), "One-hour exemption saved through UI");
                    AltHaltClient.store().checkAndRecord(ServerKey.of(althalt$server.ip), client.getUser().getProfileId(), client.getUser().getName(), java.util.Set.of("8.8.8.8"));
                }
                case 31 -> {
                    althalt$capture(client, "management-exemptions.png");
                    althalt$buttonContaining(client.gui.screen(), "Same-IP").onPress(null);
                }
                case 32 -> {
                    althalt$button(client.gui.screen(), "Revoke exemption").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().exemptions().isEmpty(), "UI revocation saved");
                    try {
                        AltHaltClient.store().checkAndRecord(ServerKey.of(althalt$server.ip), client.getUser().getProfileId(), client.getUser().getName(), java.util.Set.of("8.8.8.8"));
                        throw new AssertionError("Revoked IP exemption remained active");
                    } catch (BlockedException expected) {
                        althalt$assert(expected.rule == GuardRule.SAME_IP, "Revocation restores Same-IP check");
                    }
                    althalt$button(client.gui.screen(), "Alts").onPress(null);
                }
                case 33 -> {
                    althalt$capture(client, "management-alts.png");
                    althalt$buttonContaining(client.gui.screen(), "[Current]").onPress(null);
                }
                case 34 -> {
                    althalt$button(client.gui.screen(), "Edit label").onPress(null);
                    althalt$field(client.gui.screen(), "Display label").setValue("Builder alt");
                    althalt$button(client.gui.screen(), "Save alt").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().alts().stream().anyMatch(alt -> alt.displayName().equals("Builder alt")), "Alt label edited through UI");
                    althalt$button(client.gui.screen(), "Locks").onPress(null);
                }
                case 35 -> {
                    althalt$capture(client, "management-locks.png");
                    althalt$buttonContaining(client.gui.screen(), "localhost:").onPress(null);
                }
                case 36 -> {
                    althalt$button(client.gui.screen(), "Edit lock").onPress(null);
                    althalt$field(client.gui.screen(), "Allowed account UUID").setValue("11111111-1111-4111-8111-111111111111");
                    althalt$button(client.gui.screen(), "Save UUID lock").onPress(null);
                    althalt$button(client.gui.screen(), "Done").onPress(null);
                    althalt$assert(AltHaltClient.store().lockedAccount(ServerKey.of(althalt$server.ip)).toString().startsWith("11111111"), "Lock edited through UI");
                    althalt$buttonContaining(client.gui.screen(), "localhost:").onPress(null);
                    althalt$button(client.gui.screen(), "Remove lock").onPress(null);
                }
                case 37 -> {
                    althalt$capture(client, "remove-lock-confirm.png");
                    althalt$button(client.gui.screen(), "No").onPress(null);
                    althalt$assert(AltHaltClient.store().lockedAccount(ServerKey.of(althalt$server.ip)) != null, "Cancelled removal preserved lock");
                    althalt$button(client.gui.screen(), "Remove lock").onPress(null);
                    althalt$button(client.gui.screen(), "Yes").onPress(null);
                    althalt$assert(AltHaltClient.store().lockedAccount(ServerKey.of(althalt$server.ip)) == null, "Confirmed removal erased lock");
                    althalt$button(client.gui.screen(), "Alts").onPress(null);
                    althalt$buttonContaining(client.gui.screen(), "Other account").onPress(null);
                    althalt$button(client.gui.screen(), "Remove alt").onPress(null);
                }
                case 38 -> {
                    althalt$capture(client, "remove-alt-confirm.png");
                    althalt$button(client.gui.screen(), "No").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().alts().size() == 2, "Cancelled removal preserved alt");
                    althalt$button(client.gui.screen(), "Remove alt").onPress(null);
                    althalt$button(client.gui.screen(), "Yes").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().alts().size() == 1, "Confirmed removal erased alt");
                    althalt$assert(AltHaltClient.store().snapshot().locks().isEmpty(), "Removed alt's remaining locks erased");
                    althalt$button(client.gui.screen(), "Add alt").onPress(null);
                }
                case 39 -> {
                    althalt$field(client.gui.screen(), "Account UUID").setValue("33333333-3333-4333-8333-333333333333");
                    althalt$field(client.gui.screen(), "Display label").setValue("New alt");
                    althalt$button(client.gui.screen(), "Save alt").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().alts().size() == 2, "Alt added through UI");
                    try {
                        AltHaltClient.store().requireLock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId());
                        throw new AssertionError("Expected missing-lock fixture block");
                    } catch (BlockedException expected) {
                        var block = AltHaltClient.store().recordBlock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId(), client.getUser().getName(), expected, false);
                        ManagerScreen manager = new ManagerScreen(althalt$menu, althalt$server.ip);
                        client.gui.setScreen(manager);
                        manager.openBlock(block);
                    }
                }
                case 40 -> {
                    althalt$button(client.gui.screen(), "Exempt forever").onPress(null);
                    althalt$assert(AltHaltClient.store().snapshot().exemptions().getFirst().expiresAt() == 0, "Permanent exemption saved through UI");
                    AltHaltClient.store().requireLock(ServerKey.of(althalt$server.ip), client.getUser().getProfileId());
                    althalt$noConnection();
                    Files.writeString(Path.of("smoke-result.txt"), "PASS: connection gates, verified pings/refresh, cancellation, management tabs, one-hour/permanent exemptions, revocation, alt add/edit/remove, lock edit/remove, cancellation of removals. Blocked attempts and management actions made zero target TCP connections. IP lookup was simulated.\n");
                    althalt$cleanup();
                    client.stop();
                }
                default -> { }
            }
        } catch (Throwable failure) {
            try {
                Files.writeString(Path.of("smoke-result.txt"), "FAIL: " + failure);
                althalt$cleanup();
            } catch (Exception ignored) { }
            failure.printStackTrace();
            client.stop();
        }
    }

    @Unique private static Button althalt$button(Screen screen, String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    @Unique private static Button althalt$buttonContaining(Screen screen, String text) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().contains(text)).findFirst().orElseThrow();
    }

    @Unique private static EditBox althalt$field(Screen screen, String label) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(field -> field.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }

    @Unique private static void althalt$assert(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @Unique private void althalt$noConnection() throws Exception {
        althalt$listener.setSoTimeout(150);
        try (var socket = althalt$listener.accept()) {
            throw new AssertionError("Blocked attempt contacted the server");
        } catch (java.net.SocketTimeoutException expected) { }
    }

    @Unique private static void althalt$capture(Minecraft client, String filename) {
        Screenshot.grab(client.gameDirectory, filename, client.gameRenderer.mainRenderTarget(), 1, ignored -> {});
    }

    @Unique private void althalt$cleanup() throws Exception {
        if (althalt$pinger != null) althalt$pinger.removeAll();
        if (althalt$listener != null) althalt$listener.close();
        if (althalt$state != null) {
            Files.deleteIfExists(althalt$state.resolve("state.json"));
            Files.deleteIfExists(althalt$state.resolve("state.lock"));
            Files.deleteIfExists(althalt$state);
        }
    }
}
