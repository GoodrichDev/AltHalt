package dev.goodrich.althalt.smoke;

import dev.goodrich.althalt.core.BlockedException;
import dev.goodrich.althalt.core.PublicIpLookup;
import dev.goodrich.althalt.fixture.LookupFixture;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces external lookup only in the isolated smoke-test mod. */
@Mixin(value = PublicIpLookup.class, remap = false)
public abstract class IpLookupSmokeMixin {
    @Inject(method = "lookup", at = @At("HEAD"), cancellable = true)
    private void althalt$fixture(CallbackInfoReturnable<Set<String>> cir) throws BlockedException {
        if (LookupFixture.fail) throw new BlockedException("Simulated IP lookup failure.");
        cir.setReturnValue(Set.of("8.8.8.8"));
    }
}
