package local.cobblemonteamadvisor.mixin;

import local.cobblemonteamadvisor.CobblemonTeamAdvisor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dynamic_fps.impl.DynamicFPSMod", remap = false)
public abstract class DynamicFpsBattleMixin {
    @Inject(method = "targetFrameRate()I", at = @At("RETURN"), cancellable = true, require = 0)
    private static void advisor$keepBattleResponsive(CallbackInfoReturnable<Integer> callback) {
        if (CobblemonTeamAdvisor.keepBattleResponsive()) callback.setReturnValue(Math.max(30, callback.getReturnValue()));
    }
}
