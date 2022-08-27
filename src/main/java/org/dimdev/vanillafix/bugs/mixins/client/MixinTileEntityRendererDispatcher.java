package org.dimdev.vanillafix.bugs.mixins.client;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TileEntityRendererDispatcher.class)
public class MixinTileEntityRendererDispatcher {
    @Shadow
    public RayTraceResult cameraHitResult;

    @Inject(method = "setWorld(Lnet/minecraft/world/World;)V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;entity:Lnet/minecraft/entity/Entity;"))
    private void resetPointedEntity(World world, CallbackInfo ci) {
        this.cameraHitResult = null;
    }

}
