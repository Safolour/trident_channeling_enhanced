package trident_channeling_enhanced.mixin;

import trident_channeling_enhanced.util.ChainLightningHandler;
import trident_channeling_enhanced.util.CrossbowShotInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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

    // 【充能核心】：控制三叉戟自身是否带有高压电
    @Unique private boolean mubai_canChain = true;

    protected TridentEntityMixin(EntityType<? extends AbstractArrow> entityType, Level level) {
        super(entityType, level);
    }

    @Override public void mubai_setShotFromCrossbow(boolean shot) { this.shotFromCrossbow = shot; }
    @Override public boolean mubai_isShotFromCrossbow() { return this.shotFromCrossbow; }
    @Override public void mubai_setPowerLevel(int level) { this.powerLevel = level; }
    @Override public int mubai_getPowerLevel() { return this.powerLevel; }
    @Override public void mubai_setExtraTrident(boolean extra) { this.isExtraTrident = extra; }
    @Override public boolean mubai_isExtraTrident() { return this.isExtraTrident; }

    @ModifyVariable(method = "onHitEntity", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    private float modifyBaseDamage(float originalDamage) {
        if (this.shotFromCrossbow) {
            double speed = this.getDeltaMovement().length();
            double baseTridentDamage = 3.0;
            if (this.powerLevel > 0) baseTridentDamage += (this.powerLevel * 0.5) + 0.5;
            float calculatedDamage = (float) (speed * baseTridentDamage);
            return calculatedDamage + (calculatedDamage / 4.0f) + 1.0f;
        }
        return originalDamage;
    }

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void onPlayerTouch(Player player, CallbackInfo ci) {
        if (this.shotFromCrossbow && this.isExtraTrident && this.tickCount > 10) {
            this.discard();
            ci.cancel();
        }
    }

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
    // 击中方块 (发光修复 + 100% 狂暴电塔)
    // ==========================================
    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);

        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
            var registry = serverLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var channeling = registry.get(Enchantments.CHANNELING);

            if (channeling.isPresent()) {
                int chanLevel = EnchantmentHelper.getItemEnchantmentLevel(channeling.get(), this.getWeaponItem());
                if (chanLevel > 0) {

                    boolean spawnedLightning = false;
                    boolean chained = false;

                    // 1. 避雷针抽奖机：永远在线！不管三叉戟有没有电，只要抖了就有可能出雷！
                    boolean isLightningRod = serverLevel.getBlockState(hitResult.getBlockPos()).is(Blocks.LIGHTNING_ROD);
                    boolean canSeeSky = serverLevel.canSeeSky(hitResult.getBlockPos().above());

                    if (isLightningRod && canSeeSky) {
                        // 雷雨天 100% 狂暴，非雷雨天按概率
                        double spawnProb = serverLevel.isThundering() ? 1.0 : (chanLevel - 1) * 0.02D;
                        if (serverLevel.getRandom().nextDouble() < spawnProb) {
                            // 【核心修复】：加上 .above()，把闪电生成在避雷针的头顶，完美触发白色通电发光！
                            this.mubai_spawnRealLightningAt(serverLevel, Vec3.atBottomCenterOf(hitResult.getBlockPos().above()));
                            spawnedLightning = true;
                        }
                    }

                    // 2. 如果之前电放光了，方块撞击的判定到此结束，不再喷射连锁火花
                    if (!this.mubai_canChain) return;

                    // 3. 有电状态下的初次连锁闪电爆发
                    if (chanLevel > 1) {
                        var reachEnchantment = registry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "chain_reach")));
                        int reachLevel = reachEnchantment.isPresent() ? EnchantmentHelper.getItemEnchantmentLevel(reachEnchantment.get(), this.getWeaponItem()) : 0;
                        float chainRadius = 1.75f + (reachLevel * 0.25f);

                        List<LivingEntity> nearby = serverLevel.getEntitiesOfClass(
                                LivingEntity.class,
                                this.getBoundingBox().inflate(chainRadius),
                                entity -> !entity.equals(this.getOwner()) && entity.isAlive()
                        );

                        if (!nearby.isEmpty()) {
                            nearby.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(this)));
                            LivingEntity firstTarget = nearby.get(0);
                            LivingEntity attacker = this.getOwner() instanceof LivingEntity le ? le : null;

                            float baseDmg = 8.0f;
                            float decayedFirstDmg = baseDmg * (chanLevel - 1.0f) / chanLevel;
                            net.minecraft.world.damagesource.DamageSource source = attacker instanceof Player p ?
                                    serverLevel.damageSources().playerAttack(p) : serverLevel.damageSources().generic();

                            ChainLightningHandler.IS_CHAIN.set(true);
                            firstTarget.hurt(source, decayedFirstDmg);
                            ChainLightningHandler.IS_CHAIN.set(false);
                            this.mubai_drawTridentToTargetParticles(serverLevel, firstTarget);

                            int newTotalHits = chanLevel - 1;
                            if (newTotalHits > 1) {
                                ChainLightningHandler.startChain(serverLevel, firstTarget, attacker, newTotalHits, decayedFirstDmg, chainRadius);
                            }
                            chained = true;
                        }
                    }

                    // 4. 【拉闸断电】：爆发完之后，进入防御塔的“熄火”等待期
                    if (spawnedLightning || chained) {
                        this.mubai_canChain = false;
                        this.setBaseDamage(8.0);
                        this.shotFromCrossbow = false;
                    }
                }
            }
        }
    }

    // ==========================================
    // 击中生物
    // ==========================================
    @Inject(method = "onHitEntity", at = @At("TAIL"))
    private void onHitEntityChanneling(EntityHitResult hitResult, CallbackInfo ci) {
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel) {
            // 没电了直接装死
            if (!this.mubai_canChain) return;

            var registry = serverLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var channeling = registry.get(Enchantments.CHANNELING);

            if (channeling.isPresent()) {
                int chanLevel = EnchantmentHelper.getItemEnchantmentLevel(channeling.get(), this.getWeaponItem());

                if (chanLevel > 0 && hitResult.getEntity() instanceof LivingEntity firstTarget) {

                    boolean spawnedLightning = false;
                    boolean chained = false;
                    boolean canSeeSky = serverLevel.canSeeSky(firstTarget.blockPosition());

                    if (canSeeSky && !serverLevel.isThundering()) {
                        double spawnProb = (chanLevel - 1) * 0.02D;
                        if (serverLevel.getRandom().nextDouble() < spawnProb) {
                            this.mubai_spawnRealLightningAt(serverLevel, firstTarget.position());
                            spawnedLightning = true;
                        }
                    }

                    int newTotalHits = chanLevel - 1;
                    if (newTotalHits > 0) {
                        LivingEntity attacker = this.getOwner() instanceof LivingEntity le ? le : null;
                        var reachEnchantment = registry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "chain_reach")));
                        int reachLevel = reachEnchantment.isPresent() ? EnchantmentHelper.getItemEnchantmentLevel(reachEnchantment.get(), this.getWeaponItem()) : 0;
                        float chainRadius = 1.75f + (reachLevel * 0.25f);

                        float baseDmg = 8.0f;
                        float decayedDmg = baseDmg * (chanLevel - 1.0f) / chanLevel;
                        ChainLightningHandler.startChain(serverLevel, firstTarget, attacker, newTotalHits, decayedDmg, chainRadius);
                        chained = true;
                    }

                    if (spawnedLightning || chained) {
                        this.mubai_canChain = false;
                        this.setBaseDamage(8.0);
                        this.shotFromCrossbow = false;
                    }
                }
            }
        }
    }

    // ==========================================
    // 【复活充能机制】：天雷洗礼，满血复活
    // ==========================================
    @Override
    public void thunderHit(ServerLevel serverLevel, LightningBolt lightning) {
        super.thunderHit(serverLevel, lightning);

        // 【充电】：瞬间充满电，重新激活连锁！
        this.mubai_canChain = true;

        var registry = serverLevel.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var channeling = registry.get(Enchantments.CHANNELING);

        if (channeling.isPresent()) {
            int chanLevel = EnchantmentHelper.getItemEnchantmentLevel(channeling.get(), this.getWeaponItem());
            if (chanLevel > 1) {
                var reachEnchantment = registry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "chain_reach")));
                int reachLevel = reachEnchantment.isPresent() ? EnchantmentHelper.getItemEnchantmentLevel(reachEnchantment.get(), this.getWeaponItem()) : 0;
                float chainRadius = 1.75f + (reachLevel * 0.25f);

                List<LivingEntity> nearby = serverLevel.getEntitiesOfClass(
                        LivingEntity.class,
                        this.getBoundingBox().inflate(chainRadius),
                        entity -> !entity.equals(this.getOwner()) && entity.isAlive()
                );

                if (!nearby.isEmpty()) {
                    // 如果扫描到怪，借着雷霆之威瞬间爆射一圈连锁
                    nearby.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(this)));
                    LivingEntity firstTarget = nearby.get(0);
                    LivingEntity attacker = this.getOwner() instanceof LivingEntity le ? le : null;

                    float baseDmg = 8.0f;
                    float decayedFirstDmg = baseDmg * (chanLevel - 1.0f) / chanLevel;
                    net.minecraft.world.damagesource.DamageSource source = attacker instanceof Player p ?
                            serverLevel.damageSources().playerAttack(p) : serverLevel.damageSources().generic();

                    ChainLightningHandler.IS_CHAIN.set(true);
                    firstTarget.hurt(source, decayedFirstDmg);
                    ChainLightningHandler.IS_CHAIN.set(false);
                    this.mubai_drawTridentToTargetParticles(serverLevel, firstTarget);

                    int newTotalHits = chanLevel - 1;
                    if (newTotalHits > 1) {
                        ChainLightningHandler.startChain(serverLevel, firstTarget, attacker, newTotalHits, decayedFirstDmg, chainRadius);
                    }

                    // 【再次熄火】：打完子弹，继续等待下一道落雷的洗礼
                    this.mubai_canChain = false;
                    this.setBaseDamage(8.0);
                    this.shotFromCrossbow = false;
                }
            }
        }
    }

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
    private void mubai_spawnRealLightningAt(ServerLevel serverLevel, Vec3 pos) {
        LightningBolt lightning = EntityType.LIGHTNING_BOLT.create(serverLevel);
        if (lightning != null) {
            lightning.moveTo(pos.x, pos.y, pos.z);
            if (this.getOwner() instanceof net.minecraft.server.level.ServerPlayer player) {
                lightning.setCause(player);
            }
            lightning.setVisualOnly(false);
            serverLevel.addFreshEntity(lightning);
        }
    }
}