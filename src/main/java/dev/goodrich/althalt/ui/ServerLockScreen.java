package dev.goodrich.althalt.ui;

import dev.goodrich.althalt.AltHaltClient;
import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.ServerKey;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;

public final class ServerLockScreen extends Screen {
    private final Screen parent;
    private final String initialAddress;
    private String originalKey;
    private EditBox server;
    private EditBox uuid;
    private String status = "Enter a server and the one UUID allowed to join it.";
    private int statusColor = 0xFFB0B0B0;
    private int left;
    private int top;
    private int formWidth;

    public ServerLockScreen(Screen parent, String address) {
        this(parent, address, null);
    }

    public ServerLockScreen(Screen parent, String address, String originalKey) {
        super(Component.literal("AltHalt - server account lock"));
        this.parent = parent;
        initialAddress = address;
        this.originalKey = originalKey;
    }

    @Override
    protected void init() {
        String oldServer = server == null ? initialAddress : server.getValue();
        String oldUuid = uuid == null ? "" : uuid.getValue();
        formWidth = Math.min(360, width - 30);
        left = (width - formWidth) / 2;
        top = Math.max(24, (height - 224) / 2);
        server = new EditBox(font, left, top + 26, formWidth, 20, Component.literal("Server address"));
        server.setMaxLength(255);
        server.setHint(Component.literal("play.example.net:25565"));
        server.setValue(oldServer);
        uuid = new EditBox(font, left, top + 64, formWidth, 20, Component.literal("Allowed account UUID"));
        uuid.setMaxLength(36);
        uuid.setValue(oldUuid);
        addRenderableWidget(server);
        addRenderableWidget(uuid);
        server.setResponder(ignored -> { if (originalKey == null) loadLock(); });
        if (oldUuid.isEmpty()) loadLock();
        addRenderableWidget(Button.builder(Component.literal("Use current account"), button -> {
            uuid.setValue(minecraft.getUser().getProfileId().toString());
            setStatus("Selected " + minecraft.getUser().getName() + ". Save to apply the lock.", false);
        }).bounds(left, top + 94, formWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save UUID lock"), button -> save())
                .bounds(left, top + 120, formWidth / 3 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("IP check info"), button -> minecraft.gui.setScreen(
                new DisconnectedScreen(this, Component.literal("About AltHalt protection"), Component.literal(
                        "Servers need a UUID lock or an explicit exemption. Public IP addresses are checked with ipify before joining. "
                        + "An IP recorded for one UUID is blocked for other UUIDs unless explicitly exempted, across all servers and launcher profiles on this OS account. "
                        + "Manage recent blocks, alts, locks and scoped exemptions from Multiplayer > AltHalt. "
                        + "History begins with AltHalt and is saved before login, including failed connection attempts. "
                        + "VPN split routing, proxies and network changes can make a server see a different IP. "
                        + "AltHalt cannot guarantee anonymity. History is stored locally at " + AltHaltClient.store().path()),
                        Component.literal("Back"))))
                .bounds(left + formWidth / 3 + 1, top + 120, formWidth / 3 - 3, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + formWidth * 2 / 3 + 2, top + 120, formWidth / 3 - 3, 20).build());
        setInitialFocus(server.getValue().isEmpty() ? server : uuid);
    }

    private void loadLock() {
        try {
            UUID locked = AltHaltClient.store().lockedAccount(ServerKey.of(server.getValue()));
            uuid.setValue(locked == null ? "" : locked.toString());
            setStatus(locked == null ? "No lock yet. Joining this server is blocked."
                    : "UUID lock loaded. Saved IPs for this account: " + AltHaltClient.store().addressCount(locked), false);
        } catch (IllegalArgumentException error) {
            uuid.setValue("");
            setStatus("Enter a valid server address.", true);
        } catch (BlockedException error) {
            setStatus(error.getMessage(), true);
        }
    }

    private void save() {
        try {
            String key = ServerKey.of(server.getValue());
            String value = uuid.getValue().strip();
            UUID account = UUID.fromString(value);
            if (!account.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            AltHaltClient.store().editLock(originalKey, key, account);
            if (originalKey != null) originalKey = key;
            setStatus("Saved. Only " + account + " may join " + key + ".", false);
            statusColor = 0xFF99E080;
        } catch (IllegalArgumentException error) {
            setStatus("Enter a valid server address and a full UUID (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx).", true);
        } catch (BlockedException error) {
            setStatus(error.getMessage(), true);
        }
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusColor = error ? 0xFFFF8888 : 0xFFB0B0B0;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top - 16, 0xFFFFFFFF);
        graphics.text(font, "Server address", left, top + 14, 0xFFFFFFFF);
        graphics.text(font, "Allowed UUID", left, top + 52, 0xFFFFFFFF);
        int y = top + 150;
        for (var line : font.split(Component.literal(status), formWidth)) {
            if (y > height - 36) break;
            graphics.text(font, line, left, y, statusColor);
            y += font.lineHeight + 1;
        }
        graphics.centeredText(font, "History is shared across launcher profiles.", width / 2, height - 21, 0xFF909090);
    }
}
