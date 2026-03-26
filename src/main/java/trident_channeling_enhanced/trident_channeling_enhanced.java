package trident_channeling_enhanced;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class trident_channeling_enhanced implements ModInitializer {
	public static final String MOD_ID = "trident_channeling_enhanced";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("海神重弩增强模组启动 - 纯净追加寻宝机制 (随机等级版) 已装载！");

		LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
			String path = key.location().getPath();

			// =========================================
			// 1. 藏宝图宝藏：追加海神之引 (10%) 和 连锁增幅 (15%)
			// =========================================
			if (path.equals("chests/buried_treasure")) {
				var enchantmentRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
				var tridentShot = enchantmentRegistry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath(MOD_ID, "trident_shot")));
				var chainReach = enchantmentRegistry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath(MOD_ID, "chain_reach")));

				LootPool.Builder customPool = LootPool.lootPool()
						.setRolls(ConstantValue.exactly(1.0F));

				if (tridentShot.isPresent()) {
					customPool.add(LootItem.lootTableItem(Items.ENCHANTED_BOOK)
							// 极品宝藏：10% 概率出海神之引
							.when(LootItemRandomChanceCondition.randomChance(0.10f))
							// 海神之引永远是满级 (1级)
							.apply(new net.minecraft.world.level.storage.loot.functions.SetEnchantmentsFunction.Builder()
									.withEnchantment(tridentShot.get(), ConstantValue.exactly(1.0f))));
				}

				if (chainReach.isPresent()) {
					customPool.add(LootItem.lootTableItem(Items.ENCHANTED_BOOK)
							// 豪华赠礼：15% 概率出连锁增幅
							.when(LootItemRandomChanceCondition.randomChance(0.15f))
							// 【核心改动】：等级在 1 到 5 之间随机浮动，全看人品！
							.apply(new net.minecraft.world.level.storage.loot.functions.SetEnchantmentsFunction.Builder()
									.withEnchantment(chainReach.get(), UniformGenerator.between(1.0f, 5.0f))));
				}

				tableBuilder.withPool(customPool);
			}

			// =========================================
			// 2. 沉船宝箱：追加连锁增幅 (15%)
			// =========================================
			if (path.startsWith("chests/shipwreck")) {
				var enchantmentRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
				var chainReach = enchantmentRegistry.get(ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath(MOD_ID, "chain_reach")));

				LootPool.Builder shipwreckPool = LootPool.lootPool()
						.setRolls(ConstantValue.exactly(1.0F));

				if (chainReach.isPresent()) {
					shipwreckPool.add(LootItem.lootTableItem(Items.ENCHANTED_BOOK)
							// 普通宝箱：15% 概率出连锁增幅
							.when(LootItemRandomChanceCondition.randomChance(0.15f))
							// 沉船出的是 1~4 级盲盒
							.apply(new net.minecraft.world.level.storage.loot.functions.SetEnchantmentsFunction.Builder()
									.withEnchantment(chainReach.get(), UniformGenerator.between(1.0f, 4.0f))));
				}

				tableBuilder.withPool(shipwreckPool);
			}
		});
	}
}