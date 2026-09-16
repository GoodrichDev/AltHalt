package dev.goodrich.althalt.mixin;

import dev.goodrich.althalt.AltHaltClient;
import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.ServerKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerStatusPinger.class)
public abstract class ServerStatusPingerMixin {
    @Inject(method = "pingServer", at = @At("HEAD"), cancellable = true)
    private void althalt$checkBackgroundPing(ServerData data, Runnable onSave, Runnable onPong,
            EventLoopGroupHolder eventLoop, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        try {
            AltHaltClient.store().requireLock(ServerKey.of(data.ip), client.getUser().getProfileId());
            // Let vanilla perform the status request, including refresh verification, after the UUID check.
            return;
        } catch (BlockedException | RuntimeException failure) {
            // Missing/mismatched locks and unreadable history must never allow a background connection.
            try {
                AltHaltClient.store().recordBlock(ServerKey.of(data.ip), client.getUser().getProfileId(), client.getUser().getName(),
                        failure instanceof BlockedException blocked ? blocked : new BlockedException("The ping safety check failed.", failure), true);
            } catch (BlockedException | RuntimeException ignored) {
                // Logging failure cannot permit the ping or replace the original block.
            }
        }
        ci.cancel();
        client.execute(() -> {
            data.motd = Component.literal("AltHalt: ping blocked (account not verified)");
            data.status = Component.literal("Protected");
            data.ping = -1;
            data.playerList = java.util.List.of();
            onPong.run();
        });
    }
}
