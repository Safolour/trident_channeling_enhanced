package trident_channeling_enhanced.mixin;

import trident_channeling_enhanced.util.CrossbowShotInfo;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrossbowItem.class)
public class CrossbowItemMixin {

    // 1. 纯净生成：彻底剔除 999999 NBT 暗号
    @Inject(method = "createProjectile", at = @At("HEAD"), cancellable = true)
    private void onCreateProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean isCrit, CallbackInfoReturnable<Projectile> cir) {
        if (ammo.getItem() instanceof TridentItem) {
            ItemStack actualAmmo = ammo.copyWithCount(1);
            ThrownTrident trident = new ThrownTrident(level, shooter, actualAmmo);
            CrossbowShotInfo marker = (CrossbowShotInfo) trident;
            marker.mubai_setShotFromCrossbow(true);

            var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);

            // 力量加成
            var powerEnchantment = registry.get(net.minecraft.world.item.enchantment.Enchantments.POWER);
            if (powerEnchantment.isPresent()) {
                marker.mubai_setPowerLevel(net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(powerEnchantment.get(), weapon));
            }

            // 穿透加成
            var piercing = registry.get(net.minecraft.world.item.enchantment.Enchantments.PIERCING);
            if (piercing.isPresent()) {
                byte pLevel = (byte) net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(piercing.get(), weapon);
                if (pLevel > 0) {
                    try {
                        java.lang.reflect.Method setPierce = AbstractArrow.class.getDeclaredMethod("setPierceLevel", byte.class);
                        setPierce.setAccessible(true);
                        setPierce.invoke(trident, pLevel);
                    } catch (Exception e1) {
                        try {
                            java.lang.reflect.Field pierceField = AbstractArrow.class.getDeclaredField("pierceLevel");
                            pierceField.setAccessible(true);
                            pierceField.set(trident, pLevel);
                        } catch (Exception e2) {}
                    }
                }
            }

            if (shooter instanceof Player player && player.getAbilities().instabuild) {
                trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }

            cir.setReturnValue(trident);
        }
    }

    // 2. 动态多重判定：监听原版的循环发射逻辑
    // 无论是多重1(分裂2支) 还是多重5(分裂10支)，只要 index > 0，统统按幻影叉处理！
    @Inject(method = "shootProjectile", at = @At("HEAD"))
    private void onShootProjectile(LivingEntity shooter, Projectile projectile, int index, float velocity, float divergence, float yaw, LivingEntity target, CallbackInfo ci) {
        if (projectile instanceof ThrownTrident trident && trident instanceof CrossbowShotInfo marker) {
            if (marker.mubai_isShotFromCrossbow()) {
                if (index > 0) {
                    marker.mubai_setExtraTrident(true); // 标记为幻影叉 (落地即碎)
                    trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY; // 防捡起
                }
            }
        }
    }
}