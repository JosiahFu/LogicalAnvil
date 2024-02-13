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
		this.levelCost.set(1);

		ItemStack baseItem = this.input.getStack(0);

		if (baseItem.isEmpty()) {
			this.output.setStack(0, ItemStack.EMPTY);
			this.levelCost.set(0);
			return;
		}

		ItemStack resultItem = baseItem.copy();
		ItemStack sacrificeItem = this.input.getStack(1);

		Map<Enchantment, Integer> enchantments = EnchantmentHelper.get(resultItem);

		int newCost = 0;
		int repairCostSum = baseItem.getRepairCost() + sacrificeItem.getRepairCost();
		int renameCost = 0;


		this.repairItemUsage = 0;

		if (!sacrificeItem.isEmpty()) {
			boolean isEnchantedBook = sacrificeItem.isOf(Items.ENCHANTED_BOOK) && !EnchantedBookItem.getEnchantmentNbt(sacrificeItem).isEmpty();

			if (resultItem.isDamageable() && resultItem.getItem().canRepair(baseItem, sacrificeItem)) {
				// UNIT REPAIR

				int repairCount;
				int clampedDamage = Math.min(resultItem.getDamage(), resultItem.getMaxDamage() / 4);
				if (clampedDamage <= 0) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
				for (repairCount = 0; clampedDamage > 0 && repairCount < sacrificeItem.getCount(); ++repairCount) {
					int damageAfterRepair = resultItem.getDamage() - clampedDamage;
					resultItem.setDamage(damageAfterRepair);
					++newCost;
					clampedDamage = Math.min(resultItem.getDamage(), resultItem.getMaxDamage() / 4);
				}
				this.repairItemUsage = repairCount;
			} else {
				// COMBINATION

				// If cannot apply
				if (!(isEnchantedBook || resultItem.isOf(sacrificeItem.getItem()) && resultItem.isDamageable())) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}

				// Combination repair
				if (resultItem.isDamageable() && !isEnchantedBook) {
					int remainingDurability = baseItem.getMaxDamage() - baseItem.getDamage();
					int sacrificeDurability = sacrificeItem.getMaxDamage() - sacrificeItem.getDamage();
					int durabilityRepaired = sacrificeDurability + resultItem.getMaxDamage() * 12 / 100;
					int resultDurability = remainingDurability + durabilityRepaired;
					int resultDamage = resultItem.getMaxDamage() - resultDurability;
					if (resultDamage < 0) {
						resultDamage = 0;
					}
					if (resultDamage < resultItem.getDamage()) {
						resultItem.setDamage(resultDamage);
						newCost += 2;
					}
				}

				// Enchantment transfer
				Map<Enchantment, Integer> sacrificeEnchantments = EnchantmentHelper.get(sacrificeItem);
				boolean anyAccepted = false;
				boolean anyFailed = false;
				for (Enchantment enchantment : sacrificeEnchantments.keySet()) {
					int resultLevel;
					if (enchantment == null) continue;
					int currentLevel = enchantments.getOrDefault(enchantment, 0);
					resultLevel = currentLevel == (resultLevel = sacrificeEnchantments.get(enchantment)) ? resultLevel + 1 : Math.max(resultLevel, currentLevel);
					boolean acceptsEnchantment = enchantment.isAcceptableItem(baseItem);
					if (this.player.getAbilities().creativeMode || baseItem.isOf(Items.ENCHANTED_BOOK)) {
						acceptsEnchantment = true;
					}
					for (Enchantment enchantment2 : enchantments.keySet()) {
						if (enchantment2 == enchantment || enchantment.canCombine(enchantment2)) continue;
						acceptsEnchantment = false;
						++newCost;
					}
					if (!acceptsEnchantment) {
						anyFailed = true;
						continue;
					}
					anyAccepted = true;
					if (resultLevel > enchantment.getMaxLevel()) {
						resultLevel = enchantment.getMaxLevel();
					}
					enchantments.put(enchantment, resultLevel);
					int costMultiplier = switch (enchantment.getRarity()) {
						case COMMON -> 1;
						case UNCOMMON -> 2;
						case RARE -> 4;
						case VERY_RARE -> 8;
					};
					if (isEnchantedBook) {
						costMultiplier = Math.max(1, costMultiplier / 2);
					}
					newCost += costMultiplier * resultLevel;
					if (baseItem.getCount() > 1)
						newCost = 40;
				}
				if (anyFailed && !anyAccepted) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
			}
		}

		if (this.newItemName == null || Util.isBlank(this.newItemName)) {
			// Remove name
			if (baseItem.hasCustomName()) {
				renameCost = 1;
				newCost += renameCost;
				resultItem.removeCustomName();
			}
		} else if (!this.newItemName.equals(baseItem.getName().getString())) {
			// Rename
			renameCost = 1;
			newCost += renameCost;
			resultItem.setCustomName(Text.literal(this.newItemName));
		}
		this.levelCost.set(repairCostSum + newCost);
		if (newCost <= 0) {
			// Some sort of error catching?
			resultItem = ItemStack.EMPTY;
		}
		if (renameCost == newCost && renameCost > 0 && this.levelCost.get() >= 40) {
			// You can solely rename a tool that is too expensive for 39 levels
			this.levelCost.set(39);
		}
		if (this.levelCost.get() >= 40 && !this.player.getAbilities().creativeMode) {
			// Too expensive
			resultItem = ItemStack.EMPTY;
		}
		if (!resultItem.isEmpty()) {
			// Add previous repair cost & apply enchantments
			int resultRepairCost = resultItem.getRepairCost();
			if (!sacrificeItem.isEmpty() && resultRepairCost < sacrificeItem.getRepairCost()) {
				resultRepairCost = sacrificeItem.getRepairCost();
			}
			if (renameCost != newCost || renameCost == 0) {
				resultRepairCost = AnvilScreenHandler.getNextCost(resultRepairCost);
			}
			resultItem.setRepairCost(resultRepairCost);
			EnchantmentHelper.set(enchantments, resultItem);
		}
		this.output.setStack(0, resultItem);
		this.sendContentUpdates();
	}
}
