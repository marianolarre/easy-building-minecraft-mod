package marum.easybuilding;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.util.Lazy;

@Mod(value = EasyBuilding.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = EasyBuilding.MODID, value = Dist.CLIENT)
public class EasyBuildingClient {

	private static BlockPos lastPlacedPos;
    private static BlockPos currentProjectedBlock;

    public static final Lazy<KeyMapping> GRID_KEY = Lazy.of(() -> new KeyMapping(
        "key.easybuilding.show_grid",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_LEFT_ALT,
        "category.easybuilding.general"
    ));

    public EasyBuildingClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
    /*
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        EasyBuilding.LOGGER.info("HELLO FROM CLIENT SETUP");
        EasyBuilding.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
    */

    @SubscribeEvent // on the mod event bus only on the physical client
    public static void registerBindings(RegisterKeyMappingsEvent event) {
        event.register(GRID_KEY.get());
    }

    @SubscribeEvent // on the game event bus only on the physical client
    public static void onClientTick(ClientTickEvent.Post event) {
        while (GRID_KEY.get().consumeClick()) {
            if (PlaneMode.active) {
                // Turn off
                PlaneMode.clear();
            } else {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer player = mc.player;

                // Get block player is looking at
                Vec3 start = player.getEyePosition();
                Vec3 end = start.add(player.getLookAngle().scale(64.0));
                ClipContext context = new ClipContext(
                    start,
                    end,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player
                );

                BlockHitResult hit = player.level().clip(context);

                // If there is a block...
                if (hit.getType() == HitResult.Type.BLOCK) {
                    // Store block, face and state of what I'm looking at, and turn on Easy Building Mode
                    BlockHitResult blockHit = (BlockHitResult) hit;
                    BlockPos newBlockPos = blockHit.getBlockPos();
                    Direction newFace = blockHit.getDirection();
                    if (/*newBlockPos.equals(currentProjectedBlock) && */newFace.equals(PlaneMode.selectedFace)) {
                        PlaneMode.clear();
                    } else {
                        Level level = player.level();
                        PlaneMode.selectedBlockPos = newBlockPos;
                        PlaneMode.selectedFace = newFace;
                        PlaneMode.selectedBlockState = level.getBlockState(newBlockPos);
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
                    // If there is no block, display out of range message
                    player.displayClientMessage(
                        Component.translatable("easybuilding.plane_mode.out_of_range"),
                        true
                    );
                }
            }
        }

        if (PlaneMode.active) {
            Minecraft mc = Minecraft.getInstance();

            // Is use key is pressed, place blocks at projected position
            if (mc.options.keyUse.isDown()) {
                BlockPos currentProjectedBlockPos = getProjectedBlock();
                TryPlaceBlock(currentProjectedBlockPos);
            } else {
                lastPlacedPos = null;
                currentProjectedBlock = getProjectedBlock();
            }
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!PlaneMode.active) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics gui = event.getGuiGraphics();

        int screenWidth = mc.getWindow().getGuiScaledWidth();;
        int screenHeight = mc.getWindow().getGuiScaledHeight();;

        Component text = Component.translatable("easybuilding.plane_mode.active");

        int x = (screenWidth - mc.font.width(text)) / 2;
        int y = (screenHeight / 2) + 40;

        gui.drawString(
                mc.font,
                text,
                x,
                y,
                0xFFFFFF80,
                true
        );
    }

    private static void line(
        BufferBuilder buffer,
        PoseStack poseStack,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float red, float green, float blue, float alpha,
        float thicknessMultiplier) {

        Vec3 start = new Vec3(x1,y1,z1);
        Vec3 end = new Vec3(x2,y2,z2);
        AABB beam = new AABB(start, end).inflate(0.015*thicknessMultiplier);

        LevelRenderer.addChainedFilledBoxVertices(poseStack, buffer,
            beam.minX, beam.minY, beam.minZ,
            beam.maxX, beam.maxY, beam.maxZ,
            red, green, blue, alpha);
	}

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (!PlaneMode.active) {
            return;
        }

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        float red = 1f;
        float green = 1f;
        float blue = 1f;
        BlockPos projectedBlock = getProjectedBlock();

        if (projectedBlock == null) {
            return;
        }

        // If out of range, turn red
        if (!isInRange(projectedBlock)) {
            green = 0f;
            blue = 0f;
        }
        Vec3 planePoint = new Vec3(
            projectedBlock.getX(),
            projectedBlock.getY(),
            projectedBlock.getZ()
        );

        // Half block edge case
        Vec3 modifiedPlanePoint = new Vec3(planePoint.x, planePoint.y+PlaneMode.verticalOffset, planePoint.z);

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();

        Vec3 camPos = camera.getPosition();

        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();
        poseStack.translate(
                -camPos.x,
                -camPos.y,
                -camPos.z
        );

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        drawFaceSquare(
            buffer,
            poseStack,
            modifiedPlanePoint,
            PlaneMode.selectedFace,
            red, green, blue
        );

        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }

    private static void drawFaceSquare(
        BufferBuilder buffer,
        PoseStack poseStack,
        Vec3 blockPos,
        Direction face,
		float red,
		float green,
		float blue) {

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

		//Vec3 camCorner = corner.subtract(cameraPos);

		Vec3 p1 = corner;
		Vec3 p2 = corner.add(i);
		Vec3 p3 = corner.add(i).add(j);
		Vec3 p4 = corner.add(j);

		Vec3 t1 = corner.subtract(j);
		Vec3 t2 = corner.subtract(j).add(i);
		Vec3 r1 = corner.add(i.scale(2));
		Vec3 r2 = corner.add(j).add(i.scale(2));
		Vec3 l1 = corner.subtract(i);
		Vec3 l2 = corner.add(j).subtract(i);
		Vec3 b1 = corner.add(j.scale(2));
		Vec3 b2 = corner.add(j.scale(2)).add(i);
		float alpha = 0.3f;
        float thickness = 0.9f;

		// Cross hatch
		line(buffer, poseStack,
			(float)t1.x, (float)t1.y, (float)t1.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)t2.x, (float)t2.y, (float)t2.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)l1.x, (float)l1.y, (float)l1.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)l2.x, (float)l2.y, (float)l2.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)r1.x, (float)r1.y, (float)r1.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)r2.x, (float)r2.y, (float)r2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)b1.x, (float)b1.y, (float)b1.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, alpha, thickness);
		line(buffer, poseStack,
			(float)b2.x, (float)b2.y, (float)b2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, alpha, thickness);

		// Square
		line(buffer, poseStack,
			(float)p1.x, (float)p1.y, (float)p1.z,
			(float)p2.x, (float)p2.y, (float)p2.z,
			red, green, blue, 1f, 1f);
		line(buffer, poseStack,
			(float)p2.x, (float)p2.y, (float)p2.z,
			(float)p3.x, (float)p3.y, (float)p3.z,
			red, green, blue, 1f, 1f);
		line(buffer, poseStack,
			(float)p3.x, (float)p3.y, (float)p3.z,
			(float)p4.x, (float)p4.y, (float)p4.z,
			red, green, blue, 1f, 1f);
		line(buffer, poseStack,
			(float)p4.x, (float)p4.y, (float)p4.z,
			(float)p1.x, (float)p1.y, (float)p1.z,
			red, green, blue, 1f, 1f);
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
			PlaneMode.selectedFace.getStepX(),
			PlaneMode.selectedFace.getStepY(),
			PlaneMode.selectedFace.getStepZ()
		);

		// Plane intersection
		if (modifiedPlanePoint != null && planeNormal != null) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            Vec3 rayOrigin = player.getEyePosition();
            Vec3 rayDirection = player.getLookAngle();
			
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

		Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = player.level();

		// If there is already something there, dont place
		if (!level.getBlockState(blockPos).canBeReplaced()) {
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

			BlockState state = level.getBlockState(neighborPos);

			if (!state.canBeReplaced()) {
				targetPos = neighborPos;
				targetDirection = dir.getOpposite();
				break;
			}
		}

		if (targetPos != null) {
			Vec3 hitPos = targetPos.getCenter().add(
				new Vec3(
                    targetDirection.getStepX(),
                    targetDirection.getStepY(),
                    targetDirection.getStepZ()
                ).scale(0.5)
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
                mc.gameMode.useItemOn(
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
