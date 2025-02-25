package org.dimdev.vanillafix.bugs.mixins.client;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityRenderer.class, priority = 1500)
public class MixinEntityRenderer {

    @Shadow private Entity pointedEntity;

    /** @reason Makes the third-person view camera pass through non-solid blocks (fixes https://bugs.mojang.com/browse/MC-30845) */
    @Redirect(method = "orientCamera", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;rayTraceBlocks(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/RayTraceResult;"), expect = 0)
    private RayTraceResult rayTraceBlocks(WorldClient world, Vec3d from, Vec3d to) {
        return world.rayTraceBlocks(from, to, false, true, true);
    }

    /** @reason release the reference to the entity so that the gc can do its works */
    @Inject(method = "resetData()V", at = @At(value = "HEAD"))
    private void onResetData(CallbackInfo ci) {
        pointedEntity = null;
    }
}
