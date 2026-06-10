package marum.easybuilding.client;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

// Stores the current state of the easy building mode
public class PlaneMode {
    public static boolean active = false;
    public static BlockPos selectedBlockPos;
    public static float verticalOffset;
    public static Direction selectedFace;
    public static BlockState selectedBlockState;
    public static boolean bypassPlacementInterceptor = false;

    // Disable easy building mode
    public static void clear() {
        active = false;
        selectedBlockPos = null;
        selectedFace = null;
        selectedBlockState = null;
    }
}