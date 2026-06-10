package marum.easybuilding.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.RenderLayer;

public class EasyBuildingClient implements ClientModInitializer {

	private static KeyBinding showGridKey;
	private static MinecraftClient client;
	private static BlockPos lastPlacedPos;
	private static BlockPos currentProjectedBlock;

    @Override
    public void onInitializeClient() {

		// Create key bind
		showGridKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding(
				"key.easybuilding.show_grid",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_LEFT_ALT,
				"category.easybuilding.general"
			)
		);

		// Every client tick...
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
			this.client = client;
            if (client.player == null) {
                return;
            }

			// If keybind was pressed, toggle Easy Building Mode
            if (showGridKey.wasPressed()) {
				if (PlaneMode.active) {
					// Turn off
					PlaneMode.clear();
				} else {

					// Get block player is looking at
					Entity camera = client.getCameraEntity();
					HitResult hit = camera.raycast(
						64,
						0.0f,
						false
					);

					// If there is a block...
					if (hit.getType() == HitResult.Type.BLOCK) {
						// Store block, face and state of what I'm looking at, and turn on Easy Building Mode
						BlockHitResult blockHit = (BlockHitResult) hit;
						BlockPos newBlockPos = blockHit.getBlockPos();
						Direction newFace = blockHit.getSide();
						if (newBlockPos.equals(currentProjectedBlock) && newFace.equals(PlaneMode.selectedFace)) {
							PlaneMode.clear();
						} else {
							World world = client.world;
							PlaneMode.selectedBlockPos = newBlockPos;
							PlaneMode.selectedFace = newFace;
							PlaneMode.selectedBlockState = world.getBlockState(newBlockPos);
							PlaneMode.verticalOffset = 0f;
							// If its a slab, check if we need to offset half a block
							if (PlaneMode.selectedBlockState.getBlock() instanceof SlabBlock) {
								if (PlaneMode.selectedBlockState.get(SlabBlock.TYPE) == SlabType.TOP) {
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
						// If there is no block, display out of range message
						client.player.sendMessage(
							Text.translatable("easybuilding.plane_mode.out_of_range"),
							true
						);
					}
				}
			}

			if (PlaneMode.active) {
				// Is use key is pressed, place blocks at projected position
				if (client.options.useKey.isPressed()) {
					BlockPos currentProjectedBlockPos = getProjectedBlock();
					TryPlaceBlock(currentProjectedBlockPos);
				} else {
					lastPlacedPos = null;
					currentProjectedBlock = getProjectedBlock();
				}
			}
        });

		// Render grid square
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {

			if (!PlaneMode.active) {
				return;
			}

			VertexConsumer consumer = context.consumers().getBuffer(RenderLayer.getLines());
			Matrix4f positionMatrix = context.matrixStack().peek().getPositionMatrix();
			Matrix3f normalMatrix = context.matrixStack().peek().getNormalMatrix();

			int red = 255;
			int green = 255;
			int blue = 255;
			BlockPos projectedBlock = getProjectedBlock();
			// If out of range, turn red
			if (!isInRange(projectedBlock)) {
				green = 0;
				blue = 0;
			}
			Vec3d planePoint = new Vec3d(
				projectedBlock.getX(),
				projectedBlock.getY(),
				projectedBlock.getZ()
			);

			// Half block edge case
			Vec3d modifiedPlanePoint = new Vec3d(planePoint.x, planePoint.y+PlaneMode.verticalOffset, planePoint.z);

			// Draw square
			drawFaceSquare(
				consumer,
				positionMatrix,
				normalMatrix,
				context.camera().getPos(),
				modifiedPlanePoint,
				PlaneMode.selectedFace,
				red, green, blue);
		});

		// Draw text when mode is enabled
		HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {

			if (!PlaneMode.active) {
				return;
			}

			MinecraftClient client = MinecraftClient.getInstance();

			Text text = Text.translatable("easybuilding.plane_mode.active");

			int screenWidth = client.getWindow().getScaledWidth();
			int screenHeight = client.getWindow().getScaledHeight();

			int textWidth = client.textRenderer.getWidth(text);

			int x = (screenWidth - textWidth) / 2;
			int y = (screenHeight / 2) + 40;

			drawContext.drawTextWithShadow(
					client.textRenderer,
					text,
					x,
					y,
					0xFFFFFF80
			);
		});
	}

	private static void line(
        VertexConsumer consumer,
        Matrix4f positionMatrix,
        Matrix3f normalMatrix,
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

		consumer.vertex(positionMatrix, x1, y1, z1)
				.color(red, green, blue, alpha)
				.normal(normalMatrix, dx, dy, dz)
				.next();

		consumer.vertex(positionMatrix, x2, y2, z2)
				.color(red, green, blue, alpha)
				.normal(normalMatrix, dx, dy, dz)
				.next();
	}

	private static void drawFaceSquare(
        VertexConsumer consumer,
        Matrix4f positionMatrix,
		Matrix3f normalMatrix,
        Vec3d cameraPos,
        Vec3d blockPos,
        Direction face,
		int red,
		int green,
		int blue) {

		double bx = blockPos.getX();
		double by = blockPos.getY();
		double bz = blockPos.getZ();

		double offset = 0.001;

		Vec3d corner;
		Vec3d i;
		Vec3d j;

		switch (face) {

			case UP -> {
				corner = new Vec3d(bx,     by + 1 + offset, bz);
				i = new Vec3d(1, 0, 0);
				j = new Vec3d(0, 0, 1);
			}

			case DOWN -> {
				corner = new Vec3d(bx,     by - offset, bz);
				i = new Vec3d(1, 0, 0);
				j = new Vec3d(0, 0, 1);
			}

			case NORTH -> {
				corner = new Vec3d(bx,     by,     bz - offset);
				i = new Vec3d(1, 0, 0);
				j = new Vec3d(0, 1, 0);
			}

			case SOUTH -> {
				corner = new Vec3d(bx,     by,     bz + 1 + offset);
				i = new Vec3d(1, 0, 0);
				j = new Vec3d(0, 1, 0);
			}

			case EAST -> {
				corner = new Vec3d(bx + 1 + offset, by,     bz);
				i = new Vec3d(0, 1, 0);
				j = new Vec3d(0, 0, 1);
			}

			case WEST -> {
				corner = new Vec3d(bx - offset, by,     bz);
				i = new Vec3d(0, 1, 0);
				j = new Vec3d(0, 0, 1);
			}

			default -> {
				return;
			}
		}

		Vec3d camCorner = corner.subtract(cameraPos);

		Vec3d p1 = camCorner;
		Vec3d p2 = camCorner.add(i);
		Vec3d p3 = camCorner.add(i).add(j);
		Vec3d p4 = camCorner.add(j);

		Vec3d t1 = camCorner.subtract(j);
		Vec3d t2 = camCorner.subtract(j).add(i);
		Vec3d r1 = camCorner.add(i.multiply(2));
		Vec3d r2 = camCorner.add(j).add(i.multiply(2));
		Vec3d l1 = camCorner.subtract(i);
		Vec3d l2 = camCorner.add(j).subtract(i);
		Vec3d b1 = camCorner.add(j.multiply(2));
		Vec3d b2 = camCorner.add(j.multiply(2)).add(i);
		int alpha = 80;

		// Cross hatch
		line(consumer, positionMatrix, normalMatrix,
			(float)t1.x, (float)t1.y, (float)t1.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)t2.x, (float)t2.y, (float)t2.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)l1.x, (float)l1.y, (float)l1.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)l2.x, (float)l2.y, (float)l2.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)r1.x, (float)r1.y, (float)r1.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)r2.x, (float)r2.y, (float)r2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)b1.x, (float)b1.y, (float)b1.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, alpha);
		line(consumer, positionMatrix, normalMatrix,
			(float)b2.x, (float)b2.y, (float)b2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, alpha);

		// Square
		line(consumer, positionMatrix, normalMatrix,
			(float)p1.x, (float)p1.y, (float)p1.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, 255);
		line(consumer, positionMatrix, normalMatrix,
			(float)p2.x, (float)p2.y, (float)p2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, 255);
		line(consumer, positionMatrix, normalMatrix,
			(float)p3.x, (float)p3.y, (float)p3.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, 255);
		line(consumer, positionMatrix, normalMatrix,
			(float)p4.x, (float)p4.y, (float)p4.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, 255);
	}

	// Project view vector onto selected plane
	public static BlockPos getProjectedBlock() {
		if (!PlaneMode.active) {
			return null;
		}

		Vec3d planePoint = new Vec3d(
			PlaneMode.selectedBlockPos.getX(),
			PlaneMode.selectedBlockPos.getY(),
			PlaneMode.selectedBlockPos.getZ()
		);
		Vec3d modifiedPlanePoint = new Vec3d(planePoint.x, planePoint.y, planePoint.z);
		if (PlaneMode.selectedFace == Direction.UP) {
			modifiedPlanePoint = planePoint.add(new Vec3d(0,1,0));
		}
		if (PlaneMode.selectedFace == Direction.SOUTH) {
			modifiedPlanePoint = planePoint.add(new Vec3d(0,0,1));
		}
		if (PlaneMode.selectedFace == Direction.EAST) {
			modifiedPlanePoint = planePoint.add(new Vec3d(1,0,0));
		}
		modifiedPlanePoint = modifiedPlanePoint.add(0, PlaneMode.verticalOffset, 0);
		Vec3d planeNormal = new Vec3d(
			PlaneMode.selectedFace.getVector().getX(),
			PlaneMode.selectedFace.getVector().getY(),
			PlaneMode.selectedFace.getVector().getZ()
		);

		// Plane intersection
		if (modifiedPlanePoint != null && planeNormal != null) {
			Vec3d rayOrigin = client.gameRenderer.getCamera().getPos();

			Vec3d rayDirection = client.player.getRotationVec(1.0f);
			
			double denom = rayDirection.dotProduct(planeNormal);
			
			if (Math.abs(denom) < 0.0001) {
				// Ray is parallel, do nothing
			} else {
				double t = modifiedPlanePoint.subtract(rayOrigin).dotProduct(planeNormal) / denom;
				Vec3d planeHit = rayOrigin.add(rayDirection.multiply(t));
				if (PlaneMode.selectedFace == Direction.UP || PlaneMode.selectedFace == Direction.DOWN) {
					return new BlockPos(MathHelper.floor(planeHit.x), (int)planePoint.getY(), MathHelper.floor(planeHit.z));
				}
				if (PlaneMode.selectedFace == Direction.NORTH || PlaneMode.selectedFace == Direction.SOUTH) {
					return new BlockPos(MathHelper.floor(planeHit.x), MathHelper.floor(planeHit.y), (int)planePoint.getZ());
				}
				if (PlaneMode.selectedFace == Direction.EAST || PlaneMode.selectedFace == Direction.WEST) {
					return new BlockPos((int)planePoint.getX(), MathHelper.floor(planeHit.y), MathHelper.floor(planeHit.z));
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

		World world = client.world;

		// If there is already something there, dont place
		if (!world.getBlockState(blockPos).isReplaceable()) {
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

		Direction forward = client.player.getHorizontalFacing();

		// Make player facing direction high priority when checking neighbors, and check up and down last.
		Direction[] directionPriorities = {
			forward,
			forward.getOpposite(),
			forward.rotateYCounterclockwise(),
			forward.rotateYClockwise(),
			Direction.UP,
			Direction.DOWN
		};

		// Check for neighbors to place the block onto
		for (Direction dir : directionPriorities) {
			BlockPos neighborPos = blockPos.offset(dir);

			BlockState state = world.getBlockState(neighborPos);

			if (!state.isReplaceable()) {
				targetPos = neighborPos;
				targetDirection = dir.getOpposite();
				break;
			}
		}
		
		if (targetPos != null) {
			Vec3d hitPos = Vec3d.ofCenter(targetPos).add(
				Vec3d.of(targetDirection.getVector()).multiply(0.5)
			);

			// If its a slab or stair, retain original placement and offset the position so it only places bottom/top
			if (PlaneMode.selectedBlockState.getBlock() instanceof SlabBlock) {
				double halfOffset = (PlaneMode.selectedBlockState.get(SlabBlock.TYPE) == SlabType.TOP)
				? 0.25 : -0.25;

				hitPos = hitPos.add(new Vec3d(0, halfOffset, 0));
			}
			if (PlaneMode.selectedBlockState.getBlock() instanceof StairsBlock) {
				double halfOffset = (PlaneMode.selectedBlockState.get(StairsBlock.HALF) == BlockHalf.TOP)
				? 0.25 : -0.25;

				hitPos = hitPos.add(new Vec3d(0, halfOffset, 0));
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
				client.interactionManager.interactBlock(
					client.player,
					Hand.MAIN_HAND,
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

		double reach = client.interactionManager.getReachDistance();
		Vec3d eyePos = client.player.getEyePos();
		Vec3d target = Vec3d.ofCenter(blockPos);

		return (eyePos.squaredDistanceTo(target) < reach * reach);
	}
}