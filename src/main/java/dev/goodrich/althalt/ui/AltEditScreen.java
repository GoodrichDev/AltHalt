package dev.goodrich.althalt.ui;

import dev.goodrich.althalt.AltHaltClient;
import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.GuardStore;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AltEditScreen extends Screen {
    private final Screen parent;
    private final GuardStore.Alt alt;
    private EditBox uuid;
    private EditBox label;
    private int left;
    private int top;
    private int formWidth;

    public AltEditScreen(Screen parent, GuardStore.Alt alt) {
        super(Component.literal(alt == null ? "Add alt" : "Edit alt label"));
        this.parent = parent;
        this.alt = alt;
    }

    @Override
    protected void init() {
        String oldId = uuid == null ? (alt == null ? "" : alt.uuid().toString()) : uuid.getValue();
        String oldLabel = label == null ? (alt == null || alt.label() == null ? "" : alt.label()) : label.getValue();
        formWidth = Math.min(360, width - 30);
        left = (width - formWidth) / 2;
        top = Math.max(40, height / 2 - 70);
        uuid = addRenderableWidget(new EditBox(font, left, top + 16, formWidth, 20, Component.literal("Account UUID")));
        uuid.setMaxLength(36);
        uuid.setValue(oldId);
        uuid.setEditable(alt == null);
        label = addRenderableWidget(new EditBox(font, left, top + 58, formWidth, 20, Component.literal("Display label")));
        label.setMaxLength(64);
        label.setValue(oldLabel);
        if (alt == null) {
            addRenderableWidget(Button.builder(Component.literal("Use current account"), button -> {
                uuid.setValue(minecraft.getUser().getProfileId().toString());
                label.setValue(minecraft.getUser().getName());
            }).bounds(left, top + 88, formWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Save alt"), button -> save())
                .bounds(left, top + 118, formWidth / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(left + formWidth / 2 + 2, top + 118, formWidth / 2 - 2, 20).build());
        setInitialFocus(alt == null ? uuid : label);
    }

    private void save() {
        try {
            UUID account = UUID.fromString(uuid.getValue().strip());
            if (!account.toString().equalsIgnoreCase(uuid.getValue().strip())) throw new IllegalArgumentException();
            AltHaltClient.store().saveAlt(account, label.getValue());
            onClose();
        } catch (IllegalArgumentException error) {
            minecraft.gui.setScreen(new DetailScreen(this, "Could not save alt", "Enter a full account UUID."));
        } catch (BlockedException error) {
            minecraft.gui.setScreen(new DetailScreen(this, "Could not save alt", error.getMessage()));
        }
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top - 22, 0xFFFFFFFF);
        graphics.text(font, alt == null ? "Account UUID" : "Account UUID (identity stays fixed)", left, top + 3, 0xFFCCCCCC);
        graphics.text(font, "Display label (optional)", left, top + 45, 0xFFCCCCCC);
    }
}
