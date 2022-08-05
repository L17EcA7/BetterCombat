package net.bettercombat.mixin.client;

import com.mojang.authlib.GameProfile;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.MirrorModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import net.bettercombat.client.BetterCombatClient;
import net.bettercombat.client.animation.AdjustmentModifier;
import net.bettercombat.client.animation.CustomAnimationPlayer;
import net.bettercombat.client.animation.FirstPersonRenderHelper;
import net.bettercombat.client.animation.PoseData;
import net.bettercombat.logic.WeaponRegistry;
import net.bettercombat.client.AnimationRegistry;
import net.bettercombat.client.PlayerAttackAnimatable;
import net.bettercombat.mixin.LivingEntityAccessor;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.Optional;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin extends PlayerEntity implements PlayerAttackAnimatable {

    private final MirrorModifier poseMirrorModifier = new MirrorModifier();
    private final ModifierLayer poseContainer = new ModifierLayer(null);

    private final SpeedModifier attackSpeedModifier = new SpeedModifier();
    private final MirrorModifier attackMirrorModifier = new MirrorModifier();
    private final ModifierLayer attackContainer = new ModifierLayer(null);

    private PoseData lastPose;

    public AbstractClientPlayerEntityMixin(World world, BlockPos pos, float yaw, GameProfile profile) {
        super(world, pos, yaw, profile);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void postInit(ClientWorld world, GameProfile profile, CallbackInfo ci) {
        var stack = ((IAnimatedPlayer) this).getAnimationStack();
        stack.addAnimLayer(1, poseContainer);
        stack.addAnimLayer(2000, attackContainer);

        poseMirrorModifier.setEnabled(false);
        poseContainer.addModifier(poseMirrorModifier, 0);

        attackMirrorModifier.setEnabled(false);
        attackContainer.addModifier(createAdjustmentModifier(), 0);
        attackContainer.addModifier(attackSpeedModifier, 0);
        attackContainer.addModifier(attackMirrorModifier, 0);
    }

    @Override
    public void updateAnimationsOnTick() {
        var instance = (Object)this;
        var player = (PlayerEntity)instance;
        KeyframeAnimation newPose = null;
        if (player.handSwinging) {
            setPose(newPose); // null
            return;
        }
        if (getCurrentAnimation().isPresent() && getCurrentAnimation().get().isActive()) {
            // Restore auto body rotation upon swing - Fix issue #11
            ((LivingEntityAccessor)player).invokeTurnHead(player.getHeadYaw(), 0);
        }
        var attributes = WeaponRegistry.getAttributes(player.getMainHandStack());
        if (attributes != null && attributes.pose() != null) {
            newPose = AnimationRegistry.animations.get(attributes.pose());
        }
        setPose(newPose);
    }

    private void setPose(@Nullable KeyframeAnimation pose) {
        var mirror = shouldMirrorByMainArm();
        var newPoseData = PoseData.from(pose, mirror);
        if (lastPose != null && newPoseData.equals(lastPose)) {
            return;
        }

        if (pose == null) {
            this.poseContainer.setAnimation(null);
        } else {
            var copy = pose.mutableCopy();
            updateAnimationByCurrentActivity(copy);
            poseMirrorModifier.setEnabled(mirror);
            poseContainer.setAnimation(new KeyframeAnimationPlayer(copy.build(), 0));
        }

        lastPose = newPoseData;
    }

    @Override
    public void playAttackAnimation(String name, boolean isOffHand, float length) {
        try {
            KeyframeAnimation animation = AnimationRegistry.animations.get(name);
            var copy = animation.mutableCopy();
            updateAnimationByCurrentActivity(copy);
            copy.head.pitch.setEnabled(false);
            var speed = ((float)animation.endTick) / length;
            var mirror = isOffHand;
            if(shouldMirrorByMainArm()) {
                mirror = !mirror;
            }
            attackSpeedModifier.speed = speed;
            attackMirrorModifier.setEnabled(mirror);
            attackContainer.setAnimation(new CustomAnimationPlayer(copy.build(), 0));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private AdjustmentModifier createAdjustmentModifier() {
        var player = (PlayerEntity)this;
        return new AdjustmentModifier((partName) -> {
            // System.out.println("Player pitch: " + player.getPitch());
            float rotationX = 0;
            float rotationY = 0;
            float rotationZ = 0;
            float offsetX = 0;
            float offsetY = 0;
            float offsetZ = 0;

            if (FirstPersonRenderHelper.isRenderingFirstPersonPlayerModel) {
                var pitch = player.getPitch();
                pitch = (float) Math.toRadians(pitch);
                switch (partName) {
                    case "rightArm", "leftArm" -> {
                        rotationX = pitch;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            } else {
                var pitch = player.getPitch() / 2F;
                pitch = (float) Math.toRadians(pitch);
                switch (partName) {
                    case "body" -> {
                        rotationX = (-1F) * pitch;
                    }
                    case "rightArm", "leftArm" -> {
                        rotationX = pitch;
                    }
                    case "rightLeg", "leftLeg" -> {
                        rotationX = (-1F) * pitch;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }

            }

            return Optional.of(new AdjustmentModifier.PartModifier(
                    new Vec3f(rotationX, rotationY, rotationZ),
                    new Vec3f(offsetX, offsetY, offsetZ))
            );
        });
    }

    private void updateAnimationByCurrentActivity(KeyframeAnimation.AnimationBuilder animation) {
        var pose = getPose();
        switch (pose) {
            case STANDING -> {
            }
            case FALL_FLYING -> {
            }
            case SLEEPING -> {
            }
            case SWIMMING -> {
                configurBodyPart(animation.rightLeg, false, false);
                configurBodyPart(animation.leftLeg, false, false);
            }
            case SPIN_ATTACK -> {
            }
            case CROUCHING -> {
//                configurBodyPart(animation.head, true, false);
//                configurBodyPart(animation.rightArm, true, false);
//                configurBodyPart(animation.leftArm, true, false);
//                configurBodyPart(animation.rightLeg, false, false);
//                configurBodyPart(animation.leftLeg, false, false);
            }
            case LONG_JUMPING -> {
            }
            case DYING -> {
            }
        }
        if (isMounting()) {
            configurBodyPart(animation.rightLeg, false, false);
            configurBodyPart(animation.leftLeg, false, false);
        }
    }

    private static void configurBodyPart(KeyframeAnimation.StateCollection bodyPart, boolean isRotationEnabled, boolean isOffsetEnabled) {
        bodyPart.pitch.setEnabled(isRotationEnabled);
        bodyPart.roll.setEnabled(isRotationEnabled);
        bodyPart.yaw.setEnabled(isRotationEnabled);
        bodyPart.x.setEnabled(isOffsetEnabled);
        bodyPart.y.setEnabled(isOffsetEnabled);
        bodyPart.z.setEnabled(isOffsetEnabled);
    }

    @Override
    public void stopAttackAnimation() {
        IAnimation currentAnimation = attackContainer.getAnimation();
        if (currentAnimation != null && currentAnimation instanceof KeyframeAnimationPlayer) {
             ((KeyframeAnimationPlayer)currentAnimation).stop();
        }
    }

    private boolean isMounting() {
        return this.getVehicle() != null;
    }

    private boolean shouldMirrorByMainArm() {
        return this.getMainArm() == Arm.LEFT;
    }

    @Override
    public Optional<IAnimation> getCurrentAnimation() {
        return Optional.ofNullable(attackContainer.getAnimation());
    }
}
