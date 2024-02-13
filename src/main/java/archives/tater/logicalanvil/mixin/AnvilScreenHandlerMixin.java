package archives.tater.logicalanvil.mixin;

import archives.tater.logicalanvil.LogicalAnvil;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.*;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
		int repairCost = baseItem.getRepairCost() + sacrificeItem.getRepairCost();
		boolean repaired = false;
		boolean enchanted = false;

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
					repairCost++;
					newCost += repairCost;
					if (repairCost >= 40) {
						newCost = LogicalAnvil.TOO_EXPENSIVE_SIGNAL;
					}
					clampedDamage = Math.min(resultItem.getDamage(), resultItem.getMaxDamage() / 4);
				}
				repaired = true;
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
						repairCost += 2;
						newCost += repairCost;
						if (repairCost >= 40)
							newCost = LogicalAnvil.TOO_EXPENSIVE_SIGNAL;
					}
				}

				// Enchantment transfer
				Map<Enchantment, Integer> sacrificeEnchantments = EnchantmentHelper.get(sacrificeItem);
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
						// else
						acceptsEnchantment = false;
					}
					if (!acceptsEnchantment) {
						anyFailed = true;
						continue;
					}
					enchanted = true;
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
					if (!baseItem.isOf(Items.ENCHANTED_BOOK)) {
						// 1 << x = 2^x
						newCost += costMultiplier * (1 << (resultLevel - 1));
					}
				}
				// Cannot enchant stacked
				if (enchanted && baseItem.getCount() > 1) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
				// If all conflict
				if (anyFailed && !enchanted) {
					this.output.setStack(0, ItemStack.EMPTY);
					this.levelCost.set(0);
					return;
				}
			}
		}

		boolean renamed = false;

		if (this.newItemName == null || Util.isBlank(this.newItemName) ) {
			if (baseItem.hasCustomName()) {
				// Remove name
				renamed = true;
				resultItem.removeCustomName();
			}
		} else if (!this.newItemName.equals(baseItem.getName().getString())) {
			// Rename
			renamed = true;
			resultItem.setCustomName(Text.literal(this.newItemName));
		}

		if (renamed) {
			newCost += 1;
		}

		if (!renamed && !repaired && !enchanted) {
			resultItem = ItemStack.EMPTY;
		}

		if (newCost >= LogicalAnvil.TOO_EXPENSIVE_SIGNAL) {
			resultItem = ItemStack.EMPTY;
		}

		this.levelCost.set(newCost);
		if (!resultItem.isEmpty()) {
			// Add previous repair cost & apply enchantments
			resultItem.setRepairCost(repairCost);
			EnchantmentHelper.set(enchantments, resultItem);
		}
		this.output.setStack(0, resultItem);
		this.sendContentUpdates();
	}

	@Redirect(
			method = "canTakeOutput",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/screen/Property;get()I", ordinal = 1)
	)
	public int allowFree(Property instance) {
		return 1;
	}
}
