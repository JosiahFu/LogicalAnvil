package archives.tater.logicalanvil.mixin;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(AnvilScreenHandler.class)
abstract public class AnvilScreenHandlerMixin extends ForgingScreenHandler {
	@Final
	@Shadow
	private Property levelCost;
	@Shadow
	private int repairItemUsage;
	@Shadow
	private String newItemName;

	public AnvilScreenHandlerMixin(@Nullable ScreenHandlerType<?> type, int syncId, PlayerInventory playerInventory, ScreenHandlerContext context) {
		super(type, syncId, playerInventory, context);
	}

	/**
	 * @author ThePotatoArchivist
	 * @reason to change cost calculation. That is the whole purpose of the mod.
	 */
	@Overwrite
	public void updateResult() {
		ItemStack baseItem = this.input.getStack(0);
		this.levelCost.set(1);
		int accumCost = 0;
		int totalRepairCost = 0;
		int renameCost = 0;
		if (baseItem.isEmpty()) {
			this.output.setStack(0, ItemStack.EMPTY);
			this.levelCost.set(0);
			return;
		}
		ItemStack modBaseItem = baseItem.copy();
		ItemStack sacrificeItem = this.input.getStack(1);

		Map<Enchantment, Integer> currentEnchantments = EnchantmentHelper.get(modBaseItem);

		totalRepairCost += baseItem.getRepairCost() + (sacrificeItem.isEmpty() ? 0 : sacrificeItem.getRepairCost());
		this.repairItemUsage = 0;

		if (!sacrificeItem.isEmpty()) {
			boolean isEnchantedBook = sacrificeItem.isOf(Items.ENCHANTED_BOOK) && !EnchantedBookItem.getEnchantmentNbt(sacrificeItem).isEmpty();

			// Unit repair
			if (modBaseItem.isDamageable() && modBaseItem.getItem().canRepair(baseItem, sacrificeItem)) {
				int repairCount;
				int clampedDamage = Math.min(modBaseItem.getDamage(), modBaseItem.getMaxDamage() / 4);
				if (clampedDamage <= 0) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
				for (repairCount = 0; clampedDamage > 0 && repairCount < sacrificeItem.getCount(); ++repairCount) {
					int damageAfterRepair = modBaseItem.getDamage() - clampedDamage;
					modBaseItem.setDamage(damageAfterRepair);
					++accumCost;
					clampedDamage = Math.min(modBaseItem.getDamage(), modBaseItem.getMaxDamage() / 4);
				}
				this.repairItemUsage = repairCount;
			} else {
				if (!(isEnchantedBook || modBaseItem.isOf(sacrificeItem.getItem()) && modBaseItem.isDamageable())) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
				if (modBaseItem.isDamageable() && !isEnchantedBook) {
					int remainingDurability = baseItem.getMaxDamage() - baseItem.getDamage();
					int sacrificeDurability = sacrificeItem.getMaxDamage() - sacrificeItem.getDamage();
					int durabilityRepaired = sacrificeDurability + modBaseItem.getMaxDamage() * 12 / 100;
					int resultDurability = remainingDurability + durabilityRepaired;
					int resultDamage = modBaseItem.getMaxDamage() - resultDurability;
					if (resultDamage < 0) {
						resultDamage = 0;
					}
					if (resultDamage < modBaseItem.getDamage()) {
						modBaseItem.setDamage(resultDamage);
						accumCost += 2;
					}
				}

				Map<Enchantment, Integer> sacrificeEnchantments = EnchantmentHelper.get(sacrificeItem);
				boolean anyAccepted = false;
				boolean anyFailed = false;
				for (Enchantment enchantment : sacrificeEnchantments.keySet()) {
					int resultLevel;
					if (enchantment == null) continue;
					int currentLevel = currentEnchantments.getOrDefault(enchantment, 0);
					resultLevel = currentLevel == (resultLevel = sacrificeEnchantments.get(enchantment)) ? resultLevel + 1 : Math.max(resultLevel, currentLevel);
					boolean acceptsEnchantment = enchantment.isAcceptableItem(baseItem);
					if (this.player.getAbilities().creativeMode || baseItem.isOf(Items.ENCHANTED_BOOK)) {
						acceptsEnchantment = true;
					}
					for (Enchantment enchantment2 : currentEnchantments.keySet()) {
						if (enchantment2 == enchantment || enchantment.canCombine(enchantment2)) continue;
						acceptsEnchantment = false;
						++accumCost;
					}
					if (!acceptsEnchantment) {
						anyFailed = true;
						continue;
					}
					anyAccepted = true;
					if (resultLevel > enchantment.getMaxLevel()) {
						resultLevel = enchantment.getMaxLevel();
					}
					currentEnchantments.put(enchantment, resultLevel);
					int costMultiplier = 0;
					switch (enchantment.getRarity()) {
						case COMMON: {
							costMultiplier = 1;
							break;
						}
						case UNCOMMON: {
							costMultiplier = 2;
							break;
						}
						case RARE: {
							costMultiplier = 4;
							break;
						}
						case VERY_RARE: {
							costMultiplier = 8;
						}
					}
					if (isEnchantedBook) {
						costMultiplier = Math.max(1, costMultiplier / 2);
					}
					accumCost += costMultiplier * resultLevel;
					if (baseItem.getCount() <= 1) continue;
					accumCost = 40;
				}
				if (anyFailed && !anyAccepted) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
			}
		}
		if (this.newItemName == null || Util.isBlank(this.newItemName)) {
			if (baseItem.hasCustomName()) {
				renameCost = 1;
				accumCost += renameCost;
				modBaseItem.removeCustomName();
			}
		} else if (!this.newItemName.equals(baseItem.getName().getString())) {
			renameCost = 1;
			accumCost += renameCost;
			modBaseItem.setCustomName(Text.literal(this.newItemName));
		}
		this.levelCost.set(totalRepairCost + accumCost);
		if (accumCost <= 0) {
			modBaseItem = ItemStack.EMPTY;
		}
		if (renameCost == accumCost && renameCost > 0 && this.levelCost.get() >= 40) {
			this.levelCost.set(39);
		}
		if (this.levelCost.get() >= 40 && !this.player.getAbilities().creativeMode) {
			modBaseItem = ItemStack.EMPTY;
		}
		if (!modBaseItem.isEmpty()) {
			int baseRepairCost = modBaseItem.getRepairCost();
			if (!sacrificeItem.isEmpty() && baseRepairCost < sacrificeItem.getRepairCost()) {
				baseRepairCost = sacrificeItem.getRepairCost();
			}
			if (renameCost != accumCost || renameCost == 0) {
				baseRepairCost = AnvilScreenHandler.getNextCost(baseRepairCost);
			}
			modBaseItem.setRepairCost(baseRepairCost);
			EnchantmentHelper.set(currentEnchantments, modBaseItem);
		}
		this.output.setStack(0, modBaseItem);
		this.sendContentUpdates();
	}
}
