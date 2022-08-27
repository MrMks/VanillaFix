package org.dimdev.vanillafix.bugs.mixins;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase extends Entity {

    @Shadow private DamageSource lastDamageSource;
    @Shadow private long lastDamageStamp;

    public MixinEntityLivingBase(World worldIn) {
        super(worldIn);
    }

    @Inject(method = "onEntityUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;updatePotionEffects()V"))
    private void updateDamageSource(CallbackInfo ci) {
        if (lastDamageSource != null) {
            long dif = this.world.getTotalWorldTime() - lastDamageStamp;
            if (dif < 0L || dif > 40L) this.lastDamageSource = null;
        }
    }

}
