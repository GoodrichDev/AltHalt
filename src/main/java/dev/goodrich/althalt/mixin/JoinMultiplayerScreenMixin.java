package dev.goodrich.althalt.mixin;

import dev.goodrich.althalt.ui.ManagerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    @Shadow protected ServerSelectionList serverSelectionList;

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void althalt$addSettings(CallbackInfo ci) {
        addRenderableWidget(Button.builder(Component.literal("AltHalt"), button -> {
            var selected = serverSelectionList.getSelected();
            String address = selected instanceof ServerSelectionList.OnlineServerEntry entry ? entry.getServerData().ip : "";
            minecraft.gui.setScreen(new ManagerScreen(this, address));
        }).bounds(width - 83, 6, 76, 20).build());
    }
}
