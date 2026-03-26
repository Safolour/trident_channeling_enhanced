package trident_channeling_enhanced.mixin;

import trident_channeling_enhanced.util.ChainLightningHandler;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "hurt", at = @At("RETURN"))
    private void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;

        LivingEntity target = (LivingEntity) (Object) this;
        if (!(target.level() instanceof ServerLevel serverLevel)) return;
        if (ChainLightningHandler.IS_CHAIN.get()) return;

        var registry = serverLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var channeling = registry.getOrThrow(Enchantments.CHANNELING);

        if (source.getDirectEntity() instanceof ThrownTrident trident) {
            ItemStack stack = trident.getWeaponItem();
            int levelValue = EnchantmentHelper.getItemEnchantmentLevel(channeling, stack);

            if (levelValue > 1) {
                LivingEntity attacker = source.getEntity() instanceof LivingEntity le ? le : null;

                // 【核心：读取连锁增幅等级，计算动态范围】
                var reachEnchantment = registry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "chain_reach")));
                int reachLevel = reachEnchantment.isPresent() ? EnchantmentHelper.getItemEnchantmentLevel(reachEnchantment.get(), stack) : 0;
                float chainRadius = 1.75f + (reachLevel * 0.25f);

                ChainLightningHandler.startChain(serverLevel, target, attacker, levelValue, amount, chainRadius);
            }
        }
        else if (source.getDirectEntity() instanceof Player player && source.getEntity() == player) {
            ItemStack stack = player.getMainHandItem();

            if (stack.getItem() instanceof TridentItem) {
                int levelValue = EnchantmentHelper.getItemEnchantmentLevel(channeling, stack);

                if (levelValue > 1) {
                    int effectiveLevel = levelValue - 1;

                    if (effectiveLevel > 1) {
                        // 【核心：读取连锁增幅等级，计算动态范围】
                        var reachEnchantment = registry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "chain_reach")));
                        int reachLevel = reachEnchantment.isPresent() ? EnchantmentHelper.getItemEnchantmentLevel(reachEnchantment.get(), stack) : 0;
                        float chainRadius = 1.75f + (reachLevel * 0.25f);

                        ChainLightningHandler.startChain(serverLevel, target, player, effectiveLevel, amount, chainRadius);
                    }
                }
            }
        }
    }
}