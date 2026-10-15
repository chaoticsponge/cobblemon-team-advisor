package local.cobblemonteamadvisor.mixin;

import com.cobblemon.mod.common.client.net.battle.BattleSwitchPokemonHandler;
import com.cobblemon.mod.common.net.messages.client.battle.BattleSwitchPokemonPacket;
import local.cobblemonteamadvisor.BattleAdvisor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BattleSwitchPokemonHandler.class, remap = false)
public abstract class BattleSwitchMixin {
    @Inject(method = "handle(Lcom/cobblemon/mod/common/net/messages/client/battle/BattleSwitchPokemonPacket;Lnet/minecraft/client/Minecraft;)V",
            at = @At("HEAD"))
    private void advisor$entered(BattleSwitchPokemonPacket packet, Minecraft minecraft, CallbackInfo callback) {
        BattleAdvisor.observeEntry(packet.getNewPokemon().getUuid());
    }
}
