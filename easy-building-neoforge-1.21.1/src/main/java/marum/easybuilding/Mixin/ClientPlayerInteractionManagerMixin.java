package marum.easybuilding.Mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import marum.easybuilding.EasyBuildingClient;
import marum.easybuilding.PlaneMode;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(
        method = "useItemOn",
        at = @At("HEAD"),
        cancellable = true
    )
    private void interceptPlacement(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (PlaneMode.bypassPlacementInterceptor) {
            return;
        }

        if (!PlaneMode.active) {
            return;
        }

        BlockPos projectedPos = EasyBuildingClient.getProjectedBlock();

        if (projectedPos != null) {
            EasyBuildingClient.TryPlaceBlock(projectedPos);
        }

        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}