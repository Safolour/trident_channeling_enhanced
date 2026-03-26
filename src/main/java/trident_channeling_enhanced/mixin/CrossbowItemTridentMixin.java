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

    // 【核心修复：重构弹药搜寻，严格遵循一条时间线】
    @Unique
    private static ItemStack mubai_findHighestPriorityAmmo(LivingEntity shooter, boolean hasTridentShot) {
        // A. 判定是否为原版弹药
        Predicate<ItemStack> isVanillaAmmo = stack -> stack.is(ItemTags.ARROWS) || stack.getItem() instanceof FireworkRocketItem;

        // B. 判定是否为合规的三叉戟
        Predicate<ItemStack> isValidTrident = stack -> {
            if (!(stack.getItem() instanceof TridentItem)) return false;
            for (var entry : stack.getEnchantments().keySet()) {
                if (entry.unwrapKey().isPresent() && entry.unwrapKey().get().location().toString().equals("minecraft:riptide")) {
                    return false;
                }
            }
            return true;
        };

        // C. 将它们合二为一！只要符合其一，就是合法弹药
        Predicate<ItemStack> isValidAmmo = stack -> isVanillaAmmo.test(stack) || (hasTridentShot && isValidTrident.test(stack));

        // 严格按照优先级寻找：副手 -> 主手 -> 背包。谁在前就拿谁！
        if (isValidAmmo.test(shooter.getOffhandItem())) return shooter.getOffhandItem();
        if (isValidAmmo.test(shooter.getMainHandItem())) return shooter.getMainHandItem();

        if (shooter instanceof Player player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (isValidAmmo.test(stack)) return stack;
            }
            // 创造模式如果完全没带弹药，保底生成箭
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
                // 找到优先级最高的弹药
                ItemStack ammo = mubai_findHighestPriorityAmmo(player, true);

                // 【绝妙之处】：如果优先级最高的是三叉戟，我们才出手拦截！
                // 如果你副手拿着箭，ammo 就会是箭，这段代码什么都不做，安静地交给原版处理，完美实现优先级！
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
                boolean hasMultishot = false;
                for (var entry : crossbow.getEnchantments().keySet()) {
                    if (entry.unwrapKey().isPresent() && entry.unwrapKey().get().location().toString().equals("minecraft:multishot")) {
                        hasMultishot = true;
                        break;
                    }
                }

                int count = hasMultishot ? 3 : 1;
                List<ItemStack> loadedList = new ArrayList<>();

                for (int i = 0; i < count; i++) {
                    ItemStack singleAmmo = ammo.copyWithCount(1);

                    // ==========================================
                    // Bug 2 修复：手动扣除耐久并兼容“耐久”附魔
                    // ==========================================
                    var registry = shooter.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
                    var unbreaking = registry.get(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING);
                    int unbreakingLevel = unbreaking.isPresent() ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(unbreaking.get(), singleAmmo) : 0;

                    boolean shouldDamage = true;
                    if (unbreakingLevel > 0) {
                        // 原版武器耐久公式：有 1/(等级+1) 的概率扣除耐久
                        if (shooter.getRandom().nextInt(unbreakingLevel + 1) > 0) {
                            shouldDamage = false;
                        }
                    }

                    // 如果不是创造模式，才执行扣除
                    if (shouldDamage && !(shooter instanceof Player p && p.getAbilities().instabuild)) {
                        int currentDamage = singleAmmo.getOrDefault(DataComponents.DAMAGE, 0);
                        if (currentDamage + 1 >= singleAmmo.getMaxDamage()) {
                            // 哎呀，耐久耗尽，装填时直接碎掉了
                            singleAmmo.shrink(1);
                        } else {
                            // 安全扣除一点耐久
                            singleAmmo.set(DataComponents.DAMAGE, currentDamage + 1);
                        }
                    }

                    // ==========================================
                    // Bug 1 修复前半段：给幻影叉子打上暗号
                    // ==========================================
                    if (!singleAmmo.isEmpty()) {
                        if (i > 0) { // i=1 和 i=2 是多重射击衍生的叉子
                            // 极其隐蔽的暗号：将铁砧修复花费设为 999999
                            singleAmmo.set(DataComponents.REPAIR_COST, 999999);
                        }
                        loadedList.add(singleAmmo);
                    }
                }

                if (loadedList.isEmpty()) {
                    cir.setReturnValue(false);
                    return; // 全碎了就不装填了
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