package dev.goodrich.althalt;

import dev.goodrich.althalt.core.GuardStore;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;

public final class AltHaltClient implements ClientModInitializer {
    private static GuardStore store;

    @Override
    public void onInitializeClient() {
        store = new GuardStore(Path.of(System.getProperty("user.home"), ".althalt"));
    }

    public static GuardStore store() {
        return store;
    }
}
