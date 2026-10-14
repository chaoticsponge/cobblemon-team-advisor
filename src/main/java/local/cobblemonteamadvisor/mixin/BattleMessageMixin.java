package local.cobblemonteamadvisor.mixin;

import com.cobblemon.mod.common.client.net.battle.BattleMessageHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket;
import local.cobblemonteamadvisor.BattleAdvisor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleMessageHandler.class, remap = false)
public abstract class BattleMessageMixin {
    @Inject(method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleMessagePacket;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"))
    private void advisor$observe(BattleMessagePacket packet, Minecraft minecraft, CallbackInfo callback) {
        BattleAdvisor.observeMessages(packet.getMessages());
    }
}
