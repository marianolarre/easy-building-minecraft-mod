package marum.easybuilding.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// Stores the current state of the easy building mode
public class PlaneMode {
    public static boolean active = false;
    public static BlockPos selectedBlockPos;
    public static float verticalOffset;
    public static Direction selectedFace;
    public static BlockState selectedBlockState;
    public static ResourceKey<Level> selectedDimension;
    public static boolean bypassPlacementInterceptor = false;

    // Disable easy building mode
    public static void clear() {
        active = false;
        selectedBlockPos = null;
        selectedFace = null;
        selectedBlockState = null;
        selectedDimension = null;
        verticalOffset = 0f;
    }
}