package trident_channeling_enhanced;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;

public class trident_channeling_enhanced_Client implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// 在客户端启动时，直接向官方注册我们的自定义模型谓词
		ItemProperties.register(
				Items.CROSSBOW,
				ResourceLocation.fromNamespaceAndPath("trident_channeling_enhanced", "mubai_trident"),
				(stack, level, entity, seed) -> {

					// 获取弩当前的蓄力弹药组件
					ChargedProjectiles projectiles = stack.get(DataComponents.CHARGED_PROJECTILES);

					// 如果里面有东西，就检查是不是三叉戟
					if (projectiles != null && !projectiles.isEmpty()) {
						// 【修复爆红】1.21.1 获取弹药的方法叫 getItems()
						for (var projectile : projectiles.getItems()) {
							if (projectile.getItem() instanceof TridentItem) {
								return 1.0F; // 找到了，告诉游戏切换成装填了三叉戟的材质
							}
						}
					}
					return 0.0F; // 没装或者装的是普通箭，保持原样
				}
		);
	}
}