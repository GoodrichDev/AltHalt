package dev.goodrich.althalt.ui;

import dev.goodrich.althalt.AltHaltClient;
import dev.goodrich.althalt.ConnectionGuard;
import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.GuardRule;
import dev.goodrich.althalt.core.PublicIpLookup;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CheckingScreen extends Screen {
    private static final ExecutorService LOOKUPS = Executors.newVirtualThreadPerTaskExecutor();
    private final ConnectionGuard.Request request;
    private CompletableFuture<Set<String>> pending;
    private boolean started;
    private boolean cancelled;
    private UUID checkedAccount;
    private String checkedName;
    private String checkedServer;

    public CheckingScreen(ConnectionGuard.Request request) {
        super(Component.literal("AltHalt: checking account and IP"));
        this.request = request;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 - 100, height / 2 + 38, 200, 20).build());
        if (started) return;
        started = true;
        UUID account = minecraft.getUser().getProfileId();
        String name = minecraft.getUser().getName();
        checkedAccount = account;
        checkedName = name;
        String server;
        try {
            server = request.key();
            checkedServer = server;
            AltHaltClient.store().requireLock(server, account);
        } catch (BlockedException | RuntimeException error) {
            minecraft.execute(() -> block(error));
            return;
        }
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                return new PublicIpLookup().lookup();
            } catch (BlockedException error) {
                throw new CompletionException(error);
            }
        }, LOOKUPS).orTimeout(15, TimeUnit.SECONDS);
        pending.whenComplete((addresses, error) -> request.client().execute(() -> {
            if (cancelled || request.client().gui.screen() != this) return;
            if (!account.equals(minecraft.getUser().getProfileId()) || !name.equals(minecraft.getUser().getName())) {
                block(new BlockedException("Your account changed during the check. Start the connection again."));
                return;
            }
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                try {
                    if (AltHaltClient.store().allowLookupFailure(server, account, name)) {
                        ConnectionGuard.connect(request);
                        return;
                    }
                    block(new BlockedException(GuardRule.IP_LOOKUP, cause instanceof BlockedException ? cause.getMessage()
                            : "The IP check did not complete in time. Check your connection and try again.", null, Set.of(), cause));
                } catch (BlockedException | RuntimeException failure) {
                    block(failure);
                }
                return;
            }
            try {
                // Rechecks the lock and collision in the same transaction as the durable reservation.
                AltHaltClient.store().checkAndRecord(server, account, name, addresses);
                ConnectionGuard.connect(request);
            } catch (BlockedException | RuntimeException failure) {
                block(failure);
            }
        }));
    }

    private void block(Throwable error) {
        if (!cancelled && minecraft.gui.screen() == this) {
            BlockedException failure = error instanceof BlockedException blocked ? blocked
                    : new BlockedException("The safety check failed.", error);
            String message = failure.getMessage();
            try {
                if (checkedServer != null) AltHaltClient.store().recordBlock(checkedServer, checkedAccount, checkedName, failure, false);
            } catch (BlockedException | RuntimeException logFailure) {
                message += "\nThe recent block could not be saved because history is unavailable.";
            }
            ConnectionGuard.warn(minecraft, request.parent(), message + "\n\nReview recent blocks in Multiplayer > AltHalt.");
        }
    }

    @Override
    public void onClose() {
        cancelled = true;
        if (pending != null) pending.cancel(true);
        minecraft.gui.setScreen(request.parent());
    }

    @Override
    public void removed() {
        cancelled = true;
        if (pending != null) pending.cancel(true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 38, 0xFFFFFFFF);
        graphics.centeredText(font, "Verifying your public IP before contacting the server...", width / 2, height / 2 - 12, 0xFFB0B0B0);
    }
}
