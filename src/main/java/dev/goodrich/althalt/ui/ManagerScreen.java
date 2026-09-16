package dev.goodrich.althalt.ui;

import dev.goodrich.althalt.AltHaltClient;
import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.GuardRule;
import dev.goodrich.althalt.core.GuardStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ManagerScreen extends Screen {
    public enum Tab { BLOCKS, ALTS, LOCKS, EXEMPTIONS }
    private record Row(String label, String detail, Runnable open) {}
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private final String selectedServer;
    private Tab tab;
    private int page;
    private int pages;
    private int left;
    private int contentWidth;
    private final List<Row> rows = new ArrayList<>();
    private String error;

    public ManagerScreen(Screen parent, String selectedServer) { this(parent, selectedServer, Tab.BLOCKS); }
    public ManagerScreen(Screen parent, String selectedServer, Tab tab) {
        super(Component.literal("AltHalt - management"));
        this.parent = parent;
        this.selectedServer = selectedServer;
        this.tab = tab;
    }

    @Override
    protected void init() {
        contentWidth = Math.min(500, width - 24);
        left = (width - contentWidth) / 2;
        rows.clear();
        error = null;
        try { loadRows(AltHaltClient.store().snapshot()); }
        catch (BlockedException failure) { error = failure.getMessage(); }
        String[] titles = {"Blocks", "Alts", "Locks", "Exemptions"};
        int tabWidth = (contentWidth - 12) / 4;
        for (Tab value : Tab.values()) {
            Button button = addRenderableWidget(Button.builder(Component.literal(titles[value.ordinal()]), ignored -> {
                tab = value;
                page = 0;
                rebuildWidgets();
            }).bounds(left + value.ordinal() * (tabWidth + 4), 42, tabWidth, 20).build());
            button.active = tab != value;
        }
        int perPage = Math.max(1, (height - 148) / 28);
        pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        for (int index = page * perPage; index < Math.min(rows.size(), (page + 1) * perPage); index++) {
            Row row = rows.get(index);
            String label = font.plainSubstrByWidth(row.label(), contentWidth - 18);
            addRenderableWidget(Button.builder(Component.literal(label), button -> row.open().run())
                    .tooltip(Tooltip.create(Component.literal(row.detail())))
                    .bounds(left, 74 + (index % perPage) * 28, contentWidth, 22).build());
        }
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
                .bounds(left, height - 65, 30, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
                .bounds(left + contentWidth - 30, height - 65, 30, 20).build());
        next.active = page + 1 < pages;
        int buttonWidth = (contentWidth - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal(tab == Tab.ALTS ? "Add alt" : "Add lock"), button -> {
            minecraft.gui.setScreen(tab == Tab.ALTS ? new AltEditScreen(this, null) : new ServerLockScreen(this, ""));
        }).bounds(left, height - 36, buttonWidth, 20).build());
        Button selected = addRenderableWidget(Button.builder(Component.literal("Selected server"), button ->
                minecraft.gui.setScreen(new ServerLockScreen(this, selectedServer)))
                .bounds(left + buttonWidth + 4, height - 36, buttonWidth, 20).build());
        selected.active = !selectedServer.isBlank();
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + 2 * (buttonWidth + 4), height - 36, buttonWidth, 20).build());
    }

    private void loadRows(GuardStore.Snapshot state) {
        switch (tab) {
            case BLOCKS -> state.blocks().forEach(block -> rows.add(new Row(
                    TIME.format(Instant.ofEpochMilli(block.time())) + " | " + block.rule().title + " | " + block.server(),
                    block.name() + " | " + (block.ping() ? "Ping" : "Login") + " | " + block.count() + " attempt(s)", () -> openBlock(block))));
            case ALTS -> state.alts().forEach(alt -> rows.add(new Row(
                    (alt.uuid().equals(minecraft.getUser().getProfileId()) ? "[Current] " : "") + alt.displayName()
                            + " | " + alt.servers().size() + " locks | " + alt.addresses().size() + " IPs",
                    alt.uuid().toString(), () -> openAlt(alt))));
            case LOCKS -> state.locks().forEach((server, account) -> rows.add(new Row(server + " | " + name(state, account),
                    account.toString(), () -> openLock(server, account))));
            case EXEMPTIONS -> state.exemptions().forEach(entry -> rows.add(new Row(
                    entry.rule().title + " | " + entry.server() + " | " + expiry(entry),
                    name(state, entry.account()) + " | " + entry.account(), () -> openExemption(entry))));
        }
    }

    public void openBlock(GuardStore.BlockEntry block) {
        String body = (block.ping() ? "Background ping" : "Login") + " blocked " + TIME.format(Instant.ofEpochMilli(block.time()))
                + " (" + block.count() + " attempt(s))\n\nServer: " + block.server() + "\nAccount: " + block.name() + "\nUUID: " + block.account()
                + "\n\n" + block.detail() + "\n\n" + scope(block.rule(), block.expectedAccount(), block.addresses())
                + "\n\nExemptions apply only to this account and server. Other rules still run. Retry manually after granting one.";
        if (!block.rule().exemptible) {
            minecraft.gui.setScreen(new DetailScreen(this, "Recent block", body + "\n\nThis error must be fixed; it cannot be exempted."));
        } else {
            minecraft.gui.setScreen(new DetailScreen(this, "Recent block", body,
                    new DetailScreen.Action("Exempt 1 hour", () -> grant(block, false)),
                    new DetailScreen.Action("Exempt forever", () -> grant(block, true))));
        }
    }

    private void grant(GuardStore.BlockEntry block, boolean forever) {
        perform(() -> AltHaltClient.store().grantExemption(block.id(), forever), Tab.EXEMPTIONS);
    }

    private void openAlt(GuardStore.Alt alt) {
        String body = "Label: " + alt.displayName() + "\nLast known name: " + alt.name() + "\nUUID: " + alt.uuid()
                + "\n\nServer locks:\n" + (alt.servers().isEmpty() ? "None" : String.join("\n", alt.servers()))
                + "\n\nRecorded IPs:\n" + (alt.addresses().isEmpty() ? "None" : String.join("\n", alt.addresses().stream().sorted().toList()));
        minecraft.gui.setScreen(new DetailScreen(this, "Alt details", body,
                new DetailScreen.Action("Edit label", () -> minecraft.gui.setScreen(new AltEditScreen(this, alt))),
                new DetailScreen.Action("Remove alt", () -> confirm("Remove this alt?",
                        "Remove " + alt.displayName() + " (" + alt.uuid() + ")? This erases its IP history, server locks, related exemptions and recent blocks. "
                        + "Forgotten IPs will no longer protect this alt from reuse.", () -> AltHaltClient.store().removeAlt(alt.uuid()), Tab.ALTS))));
    }

    private void openLock(String server, UUID account) {
        minecraft.gui.setScreen(new DetailScreen(this, "Server lock", "Server: " + server + "\n\nAllowed UUID: " + account,
                new DetailScreen.Action("Edit lock", () -> minecraft.gui.setScreen(new ServerLockScreen(this, server, server))),
                new DetailScreen.Action("Remove lock", () -> confirm("Remove this lock?",
                        "Remove the lock and all exemptions for " + server + "? Joining and pings will be blocked until it is configured again. IP history is kept.",
                        () -> AltHaltClient.store().removeLock(server), Tab.LOCKS))));
    }

    private void openExemption(GuardStore.Exemption entry) {
        String body = "Rule: " + entry.rule().title + "\nServer: " + entry.server() + "\nAccount UUID: " + entry.account()
                + "\nExpires: " + expiry(entry) + "\n\n" + scope(entry.rule(), entry.expectedAccount(), entry.addresses());
        minecraft.gui.setScreen(new DetailScreen(this, "Active exemption", body,
                new DetailScreen.Action("Revoke exemption", () -> perform(() -> AltHaltClient.store().revokeExemption(entry.id()), Tab.EXEMPTIONS))));
    }

    private void confirm(String title, String message, Change change, Tab after) {
        Screen previous = minecraft.gui.screen();
        minecraft.gui.setScreen(new ConfirmScreen(accepted -> {
            if (accepted) perform(change, after);
            else minecraft.gui.setScreen(previous);
        }, Component.literal(title), Component.literal(message)));
    }

    private interface Change { void run() throws BlockedException; }
    private void perform(Change change, Tab after) {
        try {
            change.run();
            tab = after;
            page = 0;
            minecraft.gui.setScreen(this);
        } catch (BlockedException error) {
            minecraft.gui.setScreen(new DetailScreen(this, "Could not save change", error.getMessage()));
        }
    }

    private static String name(GuardStore.Snapshot state, UUID account) {
        return state.alts().stream().filter(alt -> alt.uuid().equals(account)).map(GuardStore.Alt::displayName).findFirst().orElse(account.toString());
    }
    private static String expiry(GuardStore.Exemption entry) {
        if (entry.expiresAt() == 0) return "Forever";
        return (entry.active(System.currentTimeMillis()) ? "Until " : "Expired ") + TIME.format(Instant.ofEpochMilli(entry.expiresAt()));
    }
    private static String scope(GuardRule rule, String expected, Set<String> addresses) {
        return switch (rule) {
            case UUID_LOCK -> "Allows this UUID despite " + (expected == null ? "the missing server lock." : "the server being locked to " + expected + ".")
                    + " Changing the lock revokes its UUID exemptions.";
            case SAME_IP -> "Allows reuse of only these recorded IPs:\n" + String.join("\n", addresses.stream().sorted().toList())
                    + "\nHistory is still recorded. Other conflicting IPs remain blocked.";
            case IP_LOOKUP -> "Allows joining when public-IP verification fails. No unknown IP can be compared or recorded for that attempt. "
                    + "If the lookup succeeds, Same-IP checks still run.";
            case OTHER -> "No exemption is available for this safety or storage error.";
        };
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        graphics.centeredText(font, "Current account: " + minecraft.getUser().getName(), width / 2, 27, 0xFFBBBBBB);
        if (rows.isEmpty()) {
            String text = error != null ? error : "No " + switch (tab) {
                case BLOCKS -> "recent blocks"; case ALTS -> "known alts"; case LOCKS -> "server locks"; case EXEMPTIONS -> "active exemptions";
            } + ".";
            int y = 82;
            for (var line : font.split(Component.literal(text), contentWidth)) {
                if (y > height - 80) break;
                graphics.text(font, line, left, y, error == null ? 0xFFAAAAAA : 0xFFFF8888);
                y += font.lineHeight + 2;
            }
        }
        graphics.centeredText(font, "Page " + (page + 1) + " / " + pages + " | " + rows.size() + " entries", width / 2, height - 59, 0xFFBBBBBB);
    }
}
