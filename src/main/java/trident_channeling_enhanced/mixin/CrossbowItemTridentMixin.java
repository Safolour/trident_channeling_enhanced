package trident_channeling_enhanced.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemTridentMixin {

    @Shadow private boolean startSoundPlayed;
    @Shadow private boolean midLoadSoundPlayed;

    @Unique
    private static boolean mubai_hasTridentShot(ItemStack crossbow) {
        for (var entry : crossbow.getEnchantments().keySet()) {
            if (entry.unwrapKey().isPresent() && entry.unwrapKey().get().location().toString().equals("trident_channeling_enhanced:trident_shot")) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static ItemStack mubai_findHighestPriorityAmmo(LivingEntity shooter, boolean hasTridentShot) {
        Predicate<ItemStack> isVanillaAmmo = stack -> stack.is(ItemTags.ARROWS) || stack.getItem() instanceof FireworkRocketItem;
        Predicate<ItemStack> isValidTrident = stack -> {
            if (!(stack.getItem() instanceof TridentItem)) return false;
            for (var entry : stack.getEnchantments().keySet()) {
                if (entry.unwrapKey().isPresent() && entry.unwrapKey().get().location().toString().equals("minecraft:riptide")) {
                    return false;
                }
            }
            return true;
        };

        Predicate<ItemStack> isValidAmmo = stack -> isVanillaAmmo.test(stack) || (hasTridentShot && isValidTrident.test(stack));

        if (isValidAmmo.test(shooter.getOffhandItem())) return shooter.getOffhandItem();
        if (isValidAmmo.test(shooter.getMainHandItem())) return shooter.getMainHandItem();

        if (shooter instanceof Player player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (isValidAmmo.test(stack)) return stack;
            }
            if (player.getAbilities().instabuild) return new ItemStack(Items.ARROW);
        }
        return ItemStack.EMPTY;
    }

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void onUse(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack crossbow = player.getItemInHand(hand);

        if (crossbow.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY).isEmpty()) {
            boolean hasTridentShot = mubai_hasTridentShot(crossbow);
            if (hasTridentShot) {
                ItemStack ammo = mubai_findHighestPriorityAmmo(player, true);
                if (ammo.getItem() instanceof TridentItem) {
                    this.startSoundPlayed = false;
                    this.midLoadSoundPlayed = false;
                    player.startUsingItem(hand);
                    cir.setReturnValue(InteractionResultHolder.consume(crossbow));
                }
            }
        }
    }

    @Inject(method = "tryLoadProjectiles", at = @At("HEAD"), cancellable = true)
    private static void onTryLoadProjectiles(LivingEntity shooter, ItemStack crossbow, CallbackInfoReturnable<Boolean> cir) {
        boolean hasTridentShot = mubai_hasTridentShot(crossbow);
        if (hasTridentShot) {
            ItemStack ammo = mubai_findHighestPriorityAmmo(shooter, true);

            if (!ammo.isEmpty() && ammo.getItem() instanceof TridentItem) {
                var registry = shooter.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);

                // 【核心修复：动态获取多重射击等级】
                var multishot = registry.get(net.minecraft.world.item.enchantment.Enchantments.MULTISHOT);
                int multishotLevel = multishot.isPresent() ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(multishot.get(), crossbow) : 0;

                // 【完美兼容】：1级=3支，2级=5支，3级=7支...
                int count = 1 + (multishotLevel * 2);

                List<ItemStack> loadedList = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    ItemStack singleAmmo = ammo.copyWithCount(1);

                    // 耐久判定逻辑保持不变
                    var unbreaking = registry.get(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING);
                    int unbreakingLevel = unbreaking.isPresent() ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(unbreaking.get(), singleAmmo) : 0;

                    boolean shouldDamage = true;
                    if (unbreakingLevel > 0) {
                        if (shooter.getRandom().nextInt(unbreakingLevel + 1) > 0) {
                            shouldDamage = false;
                        }
                    }

                    if (shouldDamage && !(shooter instanceof Player p && p.getAbilities().instabuild)) {
                        int currentDamage = singleAmmo.getOrDefault(DataComponents.DAMAGE, 0);
                        if (currentDamage + 1 >= singleAmmo.getMaxDamage()) {
                            singleAmmo.shrink(1);
                        } else {
                            singleAmmo.set(DataComponents.DAMAGE, currentDamage + 1);
                        }
                    }

                    // 【核心修复：删除了 999999 附魔暗号注入，直接添加弹药！】
                    if (!singleAmmo.isEmpty()) {
                        loadedList.add(singleAmmo);
                    }
                }

                if (loadedList.isEmpty()) {
                    cir.setReturnValue(false);
                    return;
                }

                crossbow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(loadedList));

                if (!(shooter instanceof Player p && p.getAbilities().instabuild)) {
                    ammo.shrink(1);
                }
                cir.setReturnValue(true);
            }
        }
    }
}