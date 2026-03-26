package trident_channeling_enhanced.util;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChainLightningHandler {
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(1);
    public static final ThreadLocal<Boolean> IS_CHAIN = ThreadLocal.withInitial(() -> false);

    public static void startChain(ServerLevel level, LivingEntity firstTarget, LivingEntity attacker, int totalHits, float baseDamage) {
        if (totalHits <= 1) return;

        Set<Entity> hitEntities = new HashSet<>();
        hitEntities.add(firstTarget);
        if (attacker != null) {
            hitEntities.add(attacker);
        }

        scheduleNextJump(level, firstTarget, attacker, totalHits, 2, baseDamage, hitEntities);
    }

    private static void scheduleNextJump(ServerLevel level, LivingEntity currentSource, LivingEntity attacker, int totalHits, int currentCount, float baseDamage, Set<Entity> hitEntities) {
        if (currentCount > totalHits) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;

        SCHEDULER.schedule(() -> {
            server.execute(() -> {
                AABB box = currentSource.getBoundingBox().inflate(3.0);
                List<Entity> candidates = level.getEntities(currentSource, box, e ->
                        e instanceof LivingEntity
                                && e != attacker
                                && !hitEntities.contains(e)
                                && e.isAlive()
                );

                if (!candidates.isEmpty()) {
                    // 【核心修复】：按离当前目标的距离，从小到大排序！
                    candidates.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(currentSource)));

                    LivingEntity nextTarget = (LivingEntity) candidates.get(0);

                    float damageMultiplier = 1.0f - ((float)(currentCount - 1) / totalHits);
                    float finalDamage = baseDamage * damageMultiplier;

                    DamageSource source = attacker instanceof Player player ?
                            level.damageSources().playerAttack(player) :
                            level.damageSources().mobAttack(attacker != null ? attacker : currentSource);

                    IS_CHAIN.set(true);
                    try {
                        nextTarget.hurt(source, finalDamage);
                    } finally {
                        IS_CHAIN.set(false);
                    }

                    spawnLineParticles(level, currentSource, nextTarget);
                    hitEntities.add(nextTarget);

                    scheduleNextJump(level, nextTarget, attacker, totalHits, currentCount + 1, baseDamage, hitEntities);
                }
            });
        }, 100, TimeUnit.MILLISECONDS);
    }

    private static void spawnLineParticles(ServerLevel level, Entity start, Entity end) {
        double distance = start.position().distanceTo(end.position());
        int particleCount = (int) (distance * 5);
        for (int i = 0; i <= particleCount; i++) {
            double progress = i / (double) particleCount;
            double startY = start.getY() + start.getBbHeight() / 2.0;
            double endY = end.getY() + end.getBbHeight() / 2.0;
            double x = start.getX() + (end.getX() - start.getX()) * progress;
            double y = startY + (endY - startY) * progress;
            double z = start.getZ() + (end.getZ() - start.getZ()) * progress;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 1, 0, 0, 0, 0);
        }
    }
}