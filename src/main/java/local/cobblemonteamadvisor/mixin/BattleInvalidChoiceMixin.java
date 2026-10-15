package local.cobblemonteamadvisor.mixin;

import com.cobblemon.mod.common.client.net.battle.BattleMadeInvalidChoiceHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMadeInvalidChoicePacket;
import local.cobblemonteamadvisor.BattleAdvisor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleMadeInvalidChoiceHandler.class, remap = false)
public abstract class BattleInvalidChoiceMixin {
    @Inject(method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleMadeInvalidChoicePacket;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"))
    private void advisor$observe(BattleMadeInvalidChoicePacket packet, Minecraft minecraft, CallbackInfo callback) {
        BattleAdvisor.observeInvalidChoice();
    }
}
