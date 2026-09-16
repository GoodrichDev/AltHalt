package dev.goodrich.althalt.ui;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Paged text keeps long server names, UUIDs, and histories usable at the minimum GUI size. */
public final class DetailScreen extends Screen {
    public record Action(String label, Runnable run) {}
    private final Screen parent;
    private final String body;
    private final List<Action> actions;
    private List<FormattedCharSequence> lines;
    private int page;
    private int perPage;
    private int left;
    private int contentWidth;

    public DetailScreen(Screen parent, String title, String body, Action... actions) {
        super(Component.literal(title));
        this.parent = parent;
        this.body = body;
        this.actions = List.of(actions);
    }

    @Override
    protected void init() {
        contentWidth = Math.min(480, width - 30);
        left = (width - contentWidth) / 2;
        lines = font.split(Component.literal(body), contentWidth);
        perPage = Math.max(1, (height - 116) / (font.lineHeight + 3));
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
                .bounds(left, height - 64, 30, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
                .bounds(left + contentWidth - 30, height - 64, 30, 20).build());
        next.active = page + 1 < pages;
        int buttonWidth = (contentWidth - actions.size() * 4) / (actions.size() + 1);
        for (int index = 0; index < actions.size(); index++) {
            Action action = actions.get(index);
            addRenderableWidget(Button.builder(Component.literal(action.label()), button -> action.run().run())
                    .bounds(left + index * (buttonWidth + 4), height - 36, buttonWidth, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(left + actions.size() * (buttonWidth + 4), height - 36, buttonWidth, 20).build());
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        for (int line = 0; line < perPage && page * perPage + line < lines.size(); line++) {
            graphics.text(font, lines.get(page * perPage + line), left, 40 + line * (font.lineHeight + 3), 0xFFDDDDDD);
        }
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        graphics.centeredText(font, "Page " + (page + 1) + " / " + pages, width / 2, height - 58, 0xFFBBBBBB);
    }
}
