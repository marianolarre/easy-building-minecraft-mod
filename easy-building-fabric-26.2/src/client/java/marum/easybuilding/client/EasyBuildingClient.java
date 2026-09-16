package marum.easybuilding.client;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
import net.minecraft.world.InteractionResult;
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
            if (client == null || client.player == null || client.level == null) {
                return;
            }

			// Clear plane if dimension changed
			if (PlaneMode.active && PlaneMode.selectedDimension != null && !PlaneMode.selectedDimension.equals(client.level.dimension())) {
				PlaneMode.clear();
				lastPlacedPos = null;
				currentProjectedBlock = null;
			}

            while (showGridKey.consumeClick()) {
                if (PlaneMode.active) {
					// Turn off
					PlaneMode.clear();
					lastPlacedPos = null;
					currentProjectedBlock = null;
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
					if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
						// Store block, face and state of what I'm looking at, and turn on Easy Building Mode
						BlockHitResult blockHit = (BlockHitResult) hit;
						BlockPos newBlockPos = blockHit.getBlockPos();
						Direction newFace = blockHit.getDirection();
						if (newBlockPos.equals(currentProjectedBlock) && newFace.equals(PlaneMode.selectedFace)) {
							PlaneMode.clear();
							lastPlacedPos = null;
							currentProjectedBlock = null;
						} else {
							ClientLevel world = client.level;
							PlaneMode.selectedBlockPos = newBlockPos;
							PlaneMode.selectedFace = newFace;
							PlaneMode.selectedBlockState = world.getBlockState(newBlockPos);
							PlaneMode.selectedDimension = world.dimension();
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
						PlaneMode.clear();
						lastPlacedPos = null;
						currentProjectedBlock = null;
                        client.player.sendSystemMessage(
                            Component.translatable("easybuilding.plane_mode.out_of_range")
                        );
					}
				}
            }

			if (PlaneMode.active) {
				if (client.options.keyUse.isDown()) {
					BlockPos currentProjectedBlockPos = getProjectedBlock();
					if (currentProjectedBlockPos != null) {
						TryPlaceBlock(currentProjectedBlockPos);
					}
				} else {
					lastPlacedPos = null;
					currentProjectedBlock = getProjectedBlock();
				}
			} else {
				lastPlacedPos = null;
				currentProjectedBlock = null;
			}
        });

		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mcClient) -> {
			PlaneMode.clear();
			lastPlacedPos = null;
			currentProjectedBlock = null;
		});

        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> {
            if (!PlaneMode.active || client == null || client.player == null || client.level == null) {
				return;
			}

			Direction face = PlaneMode.selectedFace;
			if (face == null) {
				return;
			}

			BlockPos projectedBlock = getProjectedBlock();
			if (projectedBlock == null) {
				return;
			}

			int red = 255;
			int green = 255;
			int blue = 255;
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
			Vec3 modifiedPlanePoint = new Vec3(planePoint.x, planePoint.y + PlaneMode.verticalOffset, planePoint.z);

            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 cameraEyePos = client.getCameraEntity() != null ? client.getCameraEntity().getEyePosition(tickDelta) : null;
			if (cameraEyePos == null) {
				return;
			}

			int r = red;
			int g = green;
			int b = blue;
			context.submitNodeCollector().submitCustomGeometry(
				context.poseStack(),
				RenderTypes.LINES_TRANSLUCENT,
				(pose, consumer) -> {
					if (!PlaneMode.active || face == null) {
						return;
					}
					Matrix4f positionMatrix = pose.pose();
					drawFaceSquare(
						consumer,
						positionMatrix,
						cameraEyePos,
						modifiedPlanePoint,
						face,
						r, g, b);
				}
			);
        });
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath("easy-building", "plane_mode_text"),
			(graphics, tickDelta) -> {

				Minecraft client = Minecraft.getInstance();
				if (client == null || client.player == null || !PlaneMode.active) {
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

		if (face == null || blockPos == null || cameraPos == null || consumer == null || positionMatrix == null) {
			return;
		}

		double bx = blockPos.x();
		double by = blockPos.y();
		double bz = blockPos.z();
		double offset = 0.001;

		double cx = bx;
		double cy = by;
		double cz = bz;
		double ix = 0, iy = 0, iz = 0;
		double jx = 0, jy = 0, jz = 0;

		switch (face) {
			case UP -> {
				cy = by + 1.0 + offset;
				ix = 1.0;
				jz = 1.0;
			}
			case DOWN -> {
				cy = by - offset;
				ix = 1.0;
				jz = 1.0;
			}
			case NORTH -> {
				cz = bz - offset;
				ix = 1.0;
				jy = 1.0;
			}
			case SOUTH -> {
				cz = bz + 1.0 + offset;
				ix = 1.0;
				jy = 1.0;
			}
			case EAST -> {
				cx = bx + 1.0 + offset;
				iz = 1.0;
				jy = 1.0;
			}
			case WEST -> {
				cx = bx - offset;
				iz = 1.0;
				jy = 1.0;
			}
			default -> {
				return;
			}
		}

		float p1x = (float)(cx - cameraPos.x);
		float p1y = (float)(cy - cameraPos.y);
		float p1z = (float)(cz - cameraPos.z);

		float p2x = (float)(p1x + ix);
		float p2y = (float)(p1y + iy);
		float p2z = (float)(p1z + iz);

		float p3x = (float)(p1x + ix + jx);
		float p3y = (float)(p1y + iy + jy);
		float p3z = (float)(p1z + iz + jz);

		float p4x = (float)(p1x + jx);
		float p4y = (float)(p1y + jy);
		float p4z = (float)(p1z + jz);

		float t1x = (float)(p1x - jx), t1y = (float)(p1y - jy), t1z = (float)(p1z - jz);
		float t2x = (float)(p2x - jx), t2y = (float)(p2y - jy), t2z = (float)(p2z - jz);

		float l1x = (float)(p1x - ix), l1y = (float)(p1y - iy), l1z = (float)(p1z - iz);
		float l2x = (float)(p4x - ix), l2y = (float)(p4y - iy), l2z = (float)(p4z - iz);

		float r1x = (float)(p2x + ix), r1y = (float)(p2y + iy), r1z = (float)(p2z + iz);
		float r2x = (float)(p3x + ix), r2y = (float)(p3y + iy), r2z = (float)(p3z + iz);

		float b1x = (float)(p4x + jx), b1y = (float)(p4y + jy), b1z = (float)(p4z + jz);
		float b2x = (float)(p3x + jx), b2y = (float)(p3y + jy), b2z = (float)(p3z + jz);

		int alpha = 80;

		// Cross hatch
		line(consumer, positionMatrix, t1x, t1y, t1z, p1x, p1y, p1z, red, green, blue, alpha);
		line(consumer, positionMatrix, t2x, t2y, t2z, p2x, p2y, p2z, red, green, blue, alpha);
		line(consumer, positionMatrix, l1x, l1y, l1z, p1x, p1y, p1z, red, green, blue, alpha);
		line(consumer, positionMatrix, l2x, l2y, l2z, p4x, p4y, p4z, red, green, blue, alpha);
		line(consumer, positionMatrix, r1x, r1y, r1z, p2x, p2y, p2z, red, green, blue, alpha);
		line(consumer, positionMatrix, r2x, r2y, r2z, p3x, p3y, p3z, red, green, blue, alpha);
		line(consumer, positionMatrix, b1x, b1y, b1z, p4x, p4y, p4z, red, green, blue, alpha);
		line(consumer, positionMatrix, b2x, b2y, b2z, p3x, p3y, p3z, red, green, blue, alpha);

		// Square
		line(consumer, positionMatrix, p1x, p1y, p1z, p2x, p2y, p2z, red, green, blue, 255);
		line(consumer, positionMatrix, p2x, p2y, p2z, p3x, p3y, p3z, red, green, blue, 255);
		line(consumer, positionMatrix, p3x, p3y, p3z, p4x, p4y, p4z, red, green, blue, 255);
		line(consumer, positionMatrix, p4x, p4y, p4z, p1x, p1y, p1z, red, green, blue, 255);
	}

    // Project view vector onto selected plane
	public static BlockPos getProjectedBlock() {
		if (!PlaneMode.active || PlaneMode.selectedBlockPos == null || PlaneMode.selectedFace == null) {
			return null;
		}

		if (client == null || client.player == null || client.level == null) {
			return null;
		}

		if (PlaneMode.selectedDimension != null && !PlaneMode.selectedDimension.equals(client.level.dimension())) {
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
		Vec3 rayOrigin = client.player.getEyePosition();
		Vec3 rayDirection = client.player.getViewVector(1.0F);
		
		double denom = rayDirection.dot(planeNormal);
		
		if (Math.abs(denom) < 0.0001) {
			// Ray is parallel, do nothing
			return null;
		} else {
			double t = modifiedPlanePoint.subtract(rayOrigin).dot(planeNormal) / denom;
			if (t < 0) {
				// Plane is behind the player
				return null;
			}
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

		return null;
	}

    public static void TryPlaceBlock(BlockPos blockPos) {
		if (blockPos == null || !PlaneMode.active || PlaneMode.selectedBlockState == null) {
			return;
		}

		// Out of range check
		if (!isInRange(blockPos)) {
			return;
		}

		if (client == null || client.level == null || client.player == null || client.gameMode == null) {
			return;
		}

		ClientLevel world = client.level;
        LocalPlayer player = client.player;

		// If player is not holding anything in main hand, don't place
		if (player.getMainHandItem().isEmpty()) {
			return;
		}

		// If there is already something there, dont place
		if (!world.getBlockState(blockPos).canBeReplaced()) {
			return;
		}

		// If block pos hasn't changed, stop. If it has, update it.
		if (lastPlacedPos != null && blockPos.equals(lastPlacedPos)) {
			return;
		}

		BlockPos targetPos = null;
		Direction targetDirection = null;

		Direction forward = player.getDirection();
		Direction baseDir = (PlaneMode.selectedFace != null) ? PlaneMode.selectedFace.getOpposite() : null;

		Direction[] directionPriorities;
		if (baseDir != null) {
			directionPriorities = new Direction[] {
				baseDir,
				forward,
				forward.getOpposite(),
				forward.getCounterClockWise(),
				forward.getClockWise(),
				Direction.UP,
				Direction.DOWN
			};
		} else {
			directionPriorities = new Direction[] {
				forward,
				forward.getOpposite(),
				forward.getCounterClockWise(),
				forward.getClockWise(),
				Direction.UP,
				Direction.DOWN
			};
		}

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
			Vec3 hitPos = new Vec3(
				targetPos.getX() + 0.5 + targetDirection.getStepX() * 0.5,
				targetPos.getY() + 0.5 + targetDirection.getStepY() * 0.5,
				targetPos.getZ() + 0.5 + targetDirection.getStepZ() * 0.5
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
                InteractionResult result = client.gameMode.useItemOn(
					player,
					InteractionHand.MAIN_HAND,
					hit
				);
				if (result != null && result.consumesAction()) {
					lastPlacedPos = blockPos;
					player.swing(InteractionHand.MAIN_HAND);
				}
			}
			finally {
				PlaneMode.bypassPlacementInterceptor = false;
			}
		}
	}
    
    public static boolean isInRange(BlockPos blockPos) {
		if (blockPos == null || client == null || client.player == null || client.level == null) return false;

		// Respect dimension build limits (Overworld: -64..319, Nether: 0..255, End: 0..255, Custom)
		if (client.level.isOutsideBuildHeight(blockPos)) {
			return false;
		}

		// Respect world border
		if (!client.level.getWorldBorder().isWithinBounds(blockPos)) {
			return false;
		}

        double reach = client.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		Vec3 eyePos = client.player.getEyePosition();
		double dx = eyePos.x - (blockPos.getX() + 0.5);
		double dy = eyePos.y - (blockPos.getY() + 0.5);
		double dz = eyePos.z - (blockPos.getZ() + 0.5);

		return (dx * dx + dy * dy + dz * dz < reach * reach);
	}
}