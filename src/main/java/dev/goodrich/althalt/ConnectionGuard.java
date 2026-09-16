package dev.goodrich.althalt;

import dev.goodrich.althalt.core.ServerKey;
import dev.goodrich.althalt.ui.CheckingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

public final class ConnectionGuard {
    private static final ThreadLocal<Request> PERMIT = new ThreadLocal<>();

    private ConnectionGuard() {}

    public record Request(Screen parent, Minecraft client, ServerAddress address, ServerData data,
                          boolean quickPlay, TransferState transfer) {
        public String key() {
            String host = address.getHost();
            return ServerKey.of((host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort());
        }
    }

    /** The permit exists only for the exact synchronous re-entry into vanilla's connection method. */
    public static boolean intercept(Request request) {
        if (request.equals(PERMIT.get())) {
            PERMIT.remove();
            return false;
        }
        request.client().gui.setScreen(new CheckingScreen(request));
        return true;
    }

    public static void connect(Request request) {
        PERMIT.set(request);
        try {
            ConnectScreen.startConnecting(request.parent(), request.client(), request.address(), request.data(), request.quickPlay(), request.transfer());
        } finally {
            PERMIT.remove();
        }
    }

    public static void warn(Minecraft client, Screen parent, String message) {
        client.gui.setScreen(new DisconnectedScreen(parent, Component.literal("AltHalt: connection blocked"),
                Component.literal(message), Component.literal("Back")));
    }
}
