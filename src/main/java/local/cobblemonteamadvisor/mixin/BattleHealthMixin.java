package local.cobblemonteamadvisor.mixin;

import com.cobblemon.mod.common.client.net.battle.BattleHealthChangeHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleHealthChangePacket;
import local.cobblemonteamadvisor.BattleAdvisor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleHealthChangeHandler.class, remap = false)
public abstract class BattleHealthMixin {
    @Inject(method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleHealthChangePacket;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"))
    private void advisor$observe(BattleHealthChangePacket packet, Minecraft minecraft, CallbackInfo callback) {
        BattleAdvisor.observeHealth(packet);
    }
}
