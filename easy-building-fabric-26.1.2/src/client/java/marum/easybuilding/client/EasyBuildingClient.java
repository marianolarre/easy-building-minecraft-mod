package marum.easybuilding.client;
import javax.swing.text.JTextComponent.KeyBinding;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class EasyBuildingClient implements ClientModInitializer {

	private static Minecraft client;
	private static BlockPos lastPlacedPos;
	private static BlockPos currentProjectedBlock;
  
    private static KeyMapping showGridKey;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("easybuilding", "general"));

    @Override
    public void onInitializeClient() {
        client = Minecraft.getInstance();

        showGridKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.easybuilding.show_grid",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                CATEGORY
            ));
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (showGridKey.consumeClick()) {
                if (PlaneMode.active) {
					// Turn off
					PlaneMode.clear();
				} else {

					// Get block player is looking at
                    HitResult hit = client.level.clip(
                        new ClipContext(
                            client.player.getEyePosition(1.0F),
                            client.player.getEyePosition(1.0F)
                                .add(client.player.getViewVector(1.0F).scale(64)),
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            client.player
                        )
                    );

					// If there is a block...
					if (hit.getType() == HitResult.Type.BLOCK) {
						// Store block, face and state of what I'm looking at, and turn on Easy Building Mode
						BlockHitResult blockHit = (BlockHitResult) hit;
						BlockPos newBlockPos = blockHit.getBlockPos();
						Direction newFace = blockHit.getDirection();
						if (newBlockPos.equals(currentProjectedBlock) && newFace.equals(PlaneMode.selectedFace)) {
							PlaneMode.clear();
						} else {
							ClientLevel world = Minecraft.getInstance().level;
							PlaneMode.selectedBlockPos = newBlockPos;
							PlaneMode.selectedFace = newFace;
							PlaneMode.selectedBlockState = world.getBlockState(newBlockPos);
							PlaneMode.verticalOffset = 0f;
							// If its a slab, check if we need to offset half a block
							if (PlaneMode.selectedBlockState.getBlock() instanceof SlabBlock) {
								if (PlaneMode.selectedBlockState.getValue(SlabBlock.TYPE) == SlabType.TOP) {
									if (newFace == Direction.DOWN) {
										PlaneMode.verticalOffset = 0.5f;
									}
								} else {
									if (newFace == Direction.UP) {
										PlaneMode.verticalOffset = -0.5f;
									}
								}
							}
							PlaneMode.active = true;
						}
					} else {
                        client.player.sendSystemMessage(
                            Component.translatable("easybuilding.plane_mode.out_of_range")
                        );
					}
				}
            }

			if (PlaneMode.active) {
				// Is use key is pressed, place blocks at projected position
				if (client.options.keyUse.isDown()) {
					BlockPos currentProjectedBlockPos = getProjectedBlock();
					TryPlaceBlock(currentProjectedBlockPos);
				} else {
					lastPlacedPos = null;
					currentProjectedBlock = getProjectedBlock();
				}
			}
        });

        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!PlaneMode.active) {
				return;
			}

            if (client.player == null) return;

            VertexConsumer consumer = context.bufferSource().getBuffer(RenderTypes.LINES_TRANSLUCENT);
			Matrix4f positionMatrix = context.poseStack().last().pose();

			int red = 255;
			int green = 255;
			int blue = 255;
			BlockPos projectedBlock = getProjectedBlock();
			// If out of range, turn red
			if (!isInRange(projectedBlock)) {
				green = 0;
				blue = 0;
			}
			Vec3 planePoint = new Vec3(
				projectedBlock.getX(),
				projectedBlock.getY(),
				projectedBlock.getZ()
			);

			// Half block edge case
			Vec3 modifiedPlanePoint = new Vec3(planePoint.x, planePoint.y+PlaneMode.verticalOffset, planePoint.z);

            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

			// Draw square
			drawFaceSquare(
				consumer,
				positionMatrix,
				client.getCameraEntity().getEyePosition(tickDelta),
				modifiedPlanePoint,
				PlaneMode.selectedFace,
				red, green, blue);
        });
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath("easy-building", "plane_mode_text"),
			(graphics, tickDelta) -> {

				Minecraft client = Minecraft.getInstance();
				if (client.player == null) return;

				if (!PlaneMode.active) {
					return;
				}

				Component text = Component.translatable("easybuilding.plane_mode.active");
				int screenWidth = client.getWindow().getGuiScaledWidth();
            	int screenHeight = client.getWindow().getGuiScaledHeight();
				int x = (screenWidth - client.font.width(text)) / 2;
            	int y = (screenHeight / 2) + 40;

				graphics.text(
					client.font,
					text,
					x,
					y,
					0xFFFFFF80,
					true
				);
			}
		);
    }    

    private static void line(
        VertexConsumer consumer,
        Matrix4f positionMatrix,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        int red, int green, int blue, int alpha) {

		float dx = x2 - x1;
		float dy = y2 - y1;
		float dz = z2 - z1;

		float length = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (length > 0.0001f) {
			dx /= length;
			dy /= length;
			dz /= length;
		}

		consumer.addVertex(positionMatrix, x1, y1, z1)
				.setColor(red, green, blue, alpha)
				.setNormal(dx, dy, dz)
                .setLineWidth(2f);

		consumer.addVertex(positionMatrix, x2, y2, z2)
				.setColor(red, green, blue, alpha)
				.setNormal(dx, dy, dz)
                .setLineWidth(2f);;
	}

    private static void drawFaceSquare(
        VertexConsumer consumer,
        Matrix4f positionMatrix,
        Vec3 cameraPos,
        Vec3 blockPos,
        Direction face,
		int red,
		int green,
		int blue) {

		double bx = blockPos.x();
		double by = blockPos.y();
		double bz = blockPos.z();

		double offset = 0.001;

		Vec3 corner;
		Vec3 i;
		Vec3 j;

		switch (face) {

			case UP -> {
				corner = new Vec3(bx,     by + 1 + offset, bz);
				i = new Vec3(1, 0, 0);
				j = new Vec3(0, 0, 1);
			}

			case DOWN -> {
				corner = new Vec3(bx,     by - offset, bz);
				i = new Vec3(1, 0, 0);
				j = new Vec3(0, 0, 1);
			}

			case NORTH -> {
				corner = new Vec3(bx,     by,     bz - offset);
				i = new Vec3(1, 0, 0);
				j = new Vec3(0, 1, 0);
			}

			case SOUTH -> {
				corner = new Vec3(bx,     by,     bz + 1 + offset);
				i = new Vec3(1, 0, 0);
				j = new Vec3(0, 1, 0);
			}

			case EAST -> {
				corner = new Vec3(bx + 1 + offset, by,     bz);
				i = new Vec3(0, 1, 0);
				j = new Vec3(0, 0, 1);
			}

			case WEST -> {
				corner = new Vec3(bx - offset, by,     bz);
				i = new Vec3(0, 1, 0);
				j = new Vec3(0, 0, 1);
			}

			default -> {
				return;
			}
		}

		Vec3 camCorner = corner.subtract(cameraPos);

		Vec3 p1 = camCorner;
		Vec3 p2 = camCorner.add(i);
		Vec3 p3 = camCorner.add(i).add(j);
		Vec3 p4 = camCorner.add(j);

		Vec3 t1 = camCorner.subtract(j);
		Vec3 t2 = camCorner.subtract(j).add(i);
		Vec3 r1 = camCorner.add(i.scale(2));
		Vec3 r2 = camCorner.add(j).add(i.scale(2));
		Vec3 l1 = camCorner.subtract(i);
		Vec3 l2 = camCorner.add(j).subtract(i);
		Vec3 b1 = camCorner.add(j.scale(2));
		Vec3 b2 = camCorner.add(j.scale(2)).add(i);
		int alpha = 80;

		// Cross hatch
		line(consumer, positionMatrix,
			(float)t1.x, (float)t1.y, (float)t1.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)t2.x, (float)t2.y, (float)t2.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)l1.x, (float)l1.y, (float)l1.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)l2.x, (float)l2.y, (float)l2.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)r1.x, (float)r1.y, (float)r1.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)r2.x, (float)r2.y, (float)r2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)b1.x, (float)b1.y, (float)b1.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix,
			(float)b2.x, (float)b2.y, (float)b2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, alpha);

		// Square
		line(consumer, positionMatrix,
			(float)p1.x, (float)p1.y, (float)p1.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, 255);
		line(consumer, positionMatrix,
			(float)p2.x, (float)p2.y, (float)p2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, 255);
		line(consumer, positionMatrix,
			(float)p3.x, (float)p3.y, (float)p3.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, 255);
		line(consumer, positionMatrix,
			(float)p4.x, (float)p4.y, (float)p4.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, 255);
	}

    // Project view vector onto selected plane
	public static BlockPos getProjectedBlock() {
		if (!PlaneMode.active) {
			return null;
		}

		Vec3 planePoint = new Vec3(
			PlaneMode.selectedBlockPos.getX(),
			PlaneMode.selectedBlockPos.getY(),
			PlaneMode.selectedBlockPos.getZ()
		);
		Vec3 modifiedPlanePoint = new Vec3(planePoint.x, planePoint.y, planePoint.z);
		if (PlaneMode.selectedFace == Direction.UP) {
			modifiedPlanePoint = planePoint.add(new Vec3(0,1,0));
		}
		if (PlaneMode.selectedFace == Direction.SOUTH) {
			modifiedPlanePoint = planePoint.add(new Vec3(0,0,1));
		}
		if (PlaneMode.selectedFace == Direction.EAST) {
			modifiedPlanePoint = planePoint.add(new Vec3(1,0,0));
		}
		modifiedPlanePoint = modifiedPlanePoint.add(0, PlaneMode.verticalOffset, 0);
		Vec3 planeNormal = new Vec3(
			PlaneMode.selectedFace.getUnitVec3i().getX(),
			PlaneMode.selectedFace.getUnitVec3i().getY(),
			PlaneMode.selectedFace.getUnitVec3i().getZ()
		);

		// Plane intersection
		if (modifiedPlanePoint != null && planeNormal != null) {
			Vec3 rayOrigin = client.player.getEyePosition();
			Vec3 rayDirection = client.player.getViewVector(1.0F);
			
			double denom = rayDirection.dot(planeNormal);
			
			if (Math.abs(denom) < 0.0001) {
				// Ray is parallel, do nothing
			} else {
				double t = modifiedPlanePoint.subtract(rayOrigin).dot(planeNormal) / denom;
				Vec3 planeHit = rayOrigin.add(rayDirection.scale(t));
				if (PlaneMode.selectedFace == Direction.UP || PlaneMode.selectedFace == Direction.DOWN) {
					return new BlockPos((int)Math.floor(planeHit.x), (int)planePoint.y(), (int)Math.floor(planeHit.z));
				}
				if (PlaneMode.selectedFace == Direction.NORTH || PlaneMode.selectedFace == Direction.SOUTH) {
					return new BlockPos((int)Math.floor(planeHit.x), (int)Math.floor(planeHit.y), (int)planePoint.z());
				}
				if (PlaneMode.selectedFace == Direction.EAST || PlaneMode.selectedFace == Direction.WEST) {
					return new BlockPos((int)planePoint.x(), (int)Math.floor(planeHit.y), (int)Math.floor(planeHit.z));
				}
			}
		}

		return null;
	}

    public static void TryPlaceBlock(BlockPos blockPos) {
		// Out of range check
		if (!isInRange(blockPos)) {
			return;
		}

		ClientLevel world = Minecraft.getInstance().level;

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) return;

		// If there is already something there, dont place
		if (!world.getBlockState(blockPos).canBeReplaced()) {
			return;
		}

		// If block pos hasn't changed, stop. If it has, update it.
		if (lastPlacedPos == null || (blockPos != null && !blockPos.equals(lastPlacedPos))) {
			lastPlacedPos = blockPos;
		} else {
			return;
		}

		BlockPos targetPos = null;
		Direction targetDirection = null;

		Direction forward = player.getDirection();

		// Make player facing direction high priority when checking neighbors, and check up and down last.
		Direction[] directionPriorities = {
			forward,
			forward.getOpposite(),
			forward.getCounterClockWise(),
			forward.getClockWise(),
			Direction.UP,
			Direction.DOWN
		};

		// Check for neighbors to place the block onto
		for (Direction dir : directionPriorities) {
			BlockPos neighborPos = blockPos.relative(dir);

			BlockState state = world.getBlockState(neighborPos);

			if (!state.canBeReplaced()) {
				targetPos = neighborPos;
				targetDirection = dir.getOpposite();
				break;
			}
		}

		if (targetPos != null) {
			Vec3 hitPos = targetPos.getCenter().add(
				targetDirection.getUnitVec3().scale(0.5)
			);

			// If its a slab or stair, retain original placement and offset the position so it only places bottom/top
			if (PlaneMode.selectedBlockState.getBlock() instanceof SlabBlock) {
				double halfOffset = (PlaneMode.selectedBlockState.getValue(SlabBlock.TYPE) == SlabType.TOP)
				? 0.25 : -0.25;

				hitPos = hitPos.add(new Vec3(0, halfOffset, 0));
			}
			if (PlaneMode.selectedBlockState.getBlock() instanceof StairBlock) {
				double halfOffset = (PlaneMode.selectedBlockState.getValue(StairBlock.HALF) == Half.TOP)
				? 0.25 : -0.25;

				hitPos = hitPos.add(new Vec3(0, halfOffset, 0));
			}

			// Create a fake click on a neighbor
			BlockHitResult hit = new BlockHitResult(
				hitPos,
				targetDirection,
				targetPos,
				false
			);
			PlaneMode.bypassPlacementInterceptor = true;
			try {
                client.gameMode.useItemOn(
					player,
					InteractionHand.MAIN_HAND,
					hit
				);
			}
			finally {
				PlaneMode.bypassPlacementInterceptor = false;
			}
		}
	}
    
    public static boolean isInRange(BlockPos blockPos) {
		if (blockPos == null) return false;

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) return false;

        double reach = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 eyePos = player.getEyePosition();
		Vec3 target = blockPos.getCenter();

		return (eyePos.distanceToSqr(target) < reach * reach);
	}
}