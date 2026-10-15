package local.cobblemonteamadvisor.mixin;

import com.cobblemon.mod.common.net.messages.client.pokedex.ServerConfirmedRegisterPacket;
import com.cobblemon.mod.common.pokedex.scanner.PokedexUsageContext;
import local.cobblemonteamadvisor.PokedexScanAdvisor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PokedexUsageContext.class, remap = false)
public abstract class PokedexScanMixin {
    @Inject(method = "onServerConfirmedRegister", at = @At("HEAD"))
    private void advisor$scan(ServerConfirmedRegisterPacket packet, CallbackInfo callback) {
        PokedexScanAdvisor.confirmed((PokedexUsageContext) (Object) this, packet);
    }
}
