package trident_channeling_enhanced.mixin;

import trident_channeling_enhanced.util.ChainLightningHandler;
import net.minecraft.core.registries.Registries;
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

        // 情况1：投掷
        if (source.getDirectEntity() instanceof ThrownTrident trident) {
            ItemStack stack = trident.getWeaponItem();
            int levelValue = EnchantmentHelper.getItemEnchantmentLevel(channeling, stack);

            if (levelValue > 1) {
                LivingEntity attacker = source.getEntity() instanceof LivingEntity le ? le : null;
                // 投掷：总次数直接就是附魔等级
                ChainLightningHandler.startChain(serverLevel, target, attacker, levelValue, amount);
            }
        }
        // 情况2：手打
        else if (source.getDirectEntity() instanceof Player player && source.getEntity() == player) {
            ItemStack stack = player.getMainHandItem();

            if (stack.getItem() instanceof TridentItem) {
                int levelValue = EnchantmentHelper.getItemEnchantmentLevel(channeling, stack);

                if (levelValue > 1) {
                    // 【手打砍一级】实际生效等级 = 原等级 - 1
                    int effectiveLevel = levelValue - 1;

                    // 砍完之后，如果生效等级依然大于1（也就是原等级至少得是3级才能手打出2级效果），才触发连锁
                    if (effectiveLevel > 1) {
                        ChainLightningHandler.startChain(serverLevel, target, player, effectiveLevel, amount);
                    }
                }
            }
        }
    }

    // ==========================================
    // 新增：战利品掉落逻辑 (远古守卫者100%，普通守卫者玩家击杀 1.25%)
    // ==========================================
    @Inject(method = "dropCustomDeathLoot", at = @At("TAIL"))
    private void onDropDeathLoot(ServerLevel serverLevel, DamageSource damageSource, boolean recentlyHit, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // 【判断一】：是否为玩家造成的击杀（包括玩家亲手砍死、用三叉戟扎死、射箭射死）
        boolean isPlayerKill = damageSource.getEntity() instanceof Player;
        boolean shouldDrop = false;

        // 【判断二】：掉落规则分配
        if (entity.getType() == net.minecraft.world.entity.EntityType.ELDER_GUARDIAN) {
            // 远古守卫者作为小 Boss，依然保持 100% 掉落保底（无论怎么死的）
            shouldDrop = true;
        } else if (entity.getType() == net.minecraft.world.entity.EntityType.GUARDIAN) {
            // 普通守卫者：必须是玩家亲手击杀，并且踩中 1.25% (0.0125F) 的概率
            if (isPlayerKill && serverLevel.getRandom().nextFloat() < 0.0125F) {
                shouldDrop = true;
            }
        }

        // 执行掉落
        if (shouldDrop) {
            var registry = serverLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            // 获取你注册的自定义附魔
            var tridentShot = registry.get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "trident_shot")));

            if (tridentShot.isPresent()) {
                // 完美生成一本带有 1 级“海神之引”的真实附魔书
                ItemStack book = net.minecraft.world.item.EnchantedBookItem.createForEnchantment(new net.minecraft.world.item.enchantment.EnchantmentInstance(tridentShot.get(), 1));
                entity.spawnAtLocation(book);
            }
        }
    }
}

