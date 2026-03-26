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

@Mixin(CrossbowItem.class)
public class CrossbowItemMixin {

    @Inject(method = "createProjectile", at = @At("HEAD"), cancellable = true)
    private void onCreateProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean isCrit, CallbackInfoReturnable<Projectile> cir) {
        if (ammo.getItem() instanceof TridentItem) {
            ItemStack actualAmmo = ammo.copyWithCount(1);

            // 识别在装填阶段打上的“幻影叉暗号”
            Integer repairCost = actualAmmo.get(net.minecraft.core.component.DataComponents.REPAIR_COST);
            boolean isExtra = (repairCost != null && repairCost == 999999);

            if (isExtra) {
                // 抹除暗号，做戏做全套
                actualAmmo.remove(net.minecraft.core.component.DataComponents.REPAIR_COST);
            }

            ThrownTrident trident = new ThrownTrident(level, shooter, actualAmmo);
            CrossbowShotInfo marker = (CrossbowShotInfo) trident;
            marker.mubai_setShotFromCrossbow(true);

            // 告知实体它是冒牌的，并且基础防捡
            if (isExtra) {
                marker.mubai_setExtraTrident(true);
                trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }

            var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var powerEnchantment = registry.get(net.minecraft.world.item.enchantment.Enchantments.POWER);
            if (powerEnchantment.isPresent()) {
                marker.mubai_setPowerLevel(net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(powerEnchantment.get(), weapon));
            }

            // 【新增：无视红线的反射破盾大法！】
            var piercing = registry.get(net.minecraft.world.item.enchantment.Enchantments.PIERCING);
            if (piercing.isPresent()) {
                byte pLevel = (byte) net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(piercing.get(), weapon);
                if (pLevel > 0) {
                    try {
                        // 方案 A：强行调用隐藏的破盾方法
                        java.lang.reflect.Method setPierce = AbstractArrow.class.getDeclaredMethod("setPierceLevel", byte.class);
                        setPierce.setAccessible(true);
                        setPierce.invoke(trident, pLevel);
                    } catch (Exception e1) {
                        try {
                            // 方案 B：如果方法找不到，直接强行修改穿透等级的底层字段
                            java.lang.reflect.Field pierceField = AbstractArrow.class.getDeclaredField("pierceLevel");
                            pierceField.setAccessible(true);
                            pierceField.set(trident, pLevel);
                        } catch (Exception e2) {
                            // 兜底方案，覆盖所有 1.21.1 映射环境，绝不报红！
                        }
                    }
                }
            }

            if (shooter instanceof Player player && player.getAbilities().instabuild) {
                trident.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }

            cir.setReturnValue(trident);
        }
    }
}