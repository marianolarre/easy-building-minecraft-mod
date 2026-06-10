package marum.easybuilding.client.mixin;

import marum.easybuilding.client.EasyBuildingClient;
import marum.easybuilding.client.PlaneMode;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Inject(
            method = "interactBlock",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptPlacement(
            ClientPlayerEntity player,
            Hand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir) {


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

		cir.setReturnValue(ActionResult.SUCCESS);
	}
}