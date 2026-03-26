package trident_channeling_enhanced.mixin;

import trident_channeling_enhanced.util.ChainLightningHandler;
import trident_channeling_enhanced.util.CrossbowShotInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ThrownTrident.class)
public abstract class TridentEntityMixin extends AbstractArrow implements CrossbowShotInfo {

    @Shadow private boolean dealtDamage;
    @Shadow public abstract ItemStack getWeaponItem();

    @Unique private boolean shotFromCrossbow = false;
    @Unique private int powerLevel = 0;
    @Unique private boolean isExtraTrident = false;

    protected TridentEntityMixin(EntityType<? extends AbstractArrow> entityType, Level level) {
        super(entityType, level);
    }

    @Override public void mubai_setShotFromCrossbow(boolean shot) { this.shotFromCrossbow = shot; }
    @Override public boolean mubai_isShotFromCrossbow() { return this.shotFromCrossbow; }
    @Override public void mubai_setPowerLevel(int level) { this.powerLevel = level; }
    @Override public int mubai_getPowerLevel() { return this.powerLevel; }
    @Override public void mubai_setExtraTrident(boolean extra) { this.isExtraTrident = extra; }
    @Override public boolean mubai_isExtraTrident() { return this.isExtraTrident; }

    // 1. 弩射三叉戟的伤害公式（直击怪物依然享受高额速度和力量加成）
    @ModifyVariable(method = "onHitEntity", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    private float modifyBaseDamage(float originalDamage) {
        if (this.shotFromCrossbow) {
            double speed = this.getDeltaMovement().length();
            double baseTridentDamage = 3.0;

            if (this.powerLevel > 0) {
                baseTridentDamage += (this.powerLevel * 0.5) + 0.5;
            }

            float calculatedDamage = (float) (speed * baseTridentDamage);
            calculatedDamage += (calculatedDamage / 4.0f) + 1.0f;

            return calculatedDamage;
        }
        return originalDamage;
    }

    // 2. 幻影叉接触即粉碎
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void onPlayerTouch(Player player, CallbackInfo ci) {
        if (this.shotFromCrossbow && this.isExtraTrident) {
            this.discard();
            ci.cancel();
        }
    }

    // 3. 虚空忠诚返回 (移除了多余的速度监控，回归最简)
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        Level level = this.level();
        if (!level.isClientSide() && this.getY() < level.getMinBuildHeight() - 10.0) {
            var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var loyalty = registry.get(Enchantments.LOYALTY);

            if (loyalty.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(loyalty.get(), this.getWeaponItem()) > 0) {
                this.dealtDamage = true;
            } else if (this.getY() < level.getMinBuildHeight() - 64.0) {
                this.discard();
            }
        }
    }

    // ==========================================
    // 4. 【核心判定！】纯 Java 拦截插在地上的状态
    // ==========================================
    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);

        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
            var registry = serverLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var channeling = registry.get(Enchantments.CHANNELING);

            if (channeling.isPresent()) {
                int chanLevel = EnchantmentHelper.getItemEnchantmentLevel(channeling.get(), this.getWeaponItem());

                if (chanLevel > 1) {
                    List<LivingEntity> nearby = serverLevel.getEntitiesOfClass(
                            LivingEntity.class,
                            this.getBoundingBox().inflate(4.0),
                            entity -> !entity.equals(this.getOwner()) && entity.isAlive()
                    );

                    if (!nearby.isEmpty()) {
                        LivingEntity firstTarget = nearby.get(0);
                        LivingEntity attacker = this.getOwner() instanceof LivingEntity le ? le : null;

                        // 【空枪惩罚】：只要没直接扎到怪，落地引发的闪电链一律按原版 8.0 基础伤害计算！
                        float baseDmg = 8.0f;

                        // 伤害衰减计算 (传导第一次依然进行标准的数学衰减)
                        float decayedFirstDmg = baseDmg * (chanLevel - 1.0f) / chanLevel;

                        net.minecraft.world.damagesource.DamageSource source = attacker instanceof Player p ?
                                serverLevel.damageSources().playerAttack(p) :
                                serverLevel.damageSources().generic();

                        ChainLightningHandler.IS_CHAIN.set(true);
                        firstTarget.hurt(source, decayedFirstDmg);
                        ChainLightningHandler.IS_CHAIN.set(false);

                        // 绘制粒子连线
                        this.mubai_drawTridentToTargetParticles(serverLevel, firstTarget);

                        // 概率落雷
                        if (!serverLevel.isThundering()) {
                            double spawnProb = (chanLevel - 1) * 0.02D;
                            if (serverLevel.getRandom().nextDouble() < spawnProb) {
                                this.mubai_spawnRealLightningAtTrident(serverLevel);
                            }
                        }

                        // 将后续的弹射次数-1，传入衰减后的基础伤害
                        int newTotalHits = chanLevel - 1;
                        if (newTotalHits > 1) {
                            ChainLightningHandler.startChain(serverLevel, firstTarget, attacker, newTotalHits, decayedFirstDmg);
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // 【私有辅助方法】
    // ==========================================

    @Unique
    private void mubai_drawTridentToTargetParticles(ServerLevel serverLevel, LivingEntity target) {
        Vec3 start = this.position().add(0, 0.5, 0);
        Vec3 end = target.getBoundingBox().getCenter();
        double distance = start.distanceTo(end);
        int particleCount = (int) (distance * 5);
        for (int i = 0; i <= particleCount; i++) {
            double progress = i / (double) particleCount;
            double x = start.x + (end.x - start.x) * progress;
            double y = start.y + (end.y - start.y) * progress;
            double z = start.z + (end.z - start.z) * progress;
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    @Unique
    private void mubai_spawnRealLightningAtTrident(ServerLevel serverLevel) {
        LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(serverLevel);
        if (lightning != null) {
            lightning.moveTo(this.getX(), this.getY(), this.getZ());
            if (this.getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
                lightning.setCause(player);
            }
            lightning.setVisualOnly(false);
            serverLevel.addFreshEntity(lightning);
        }
    }
}