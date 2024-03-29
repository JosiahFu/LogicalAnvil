package archives.tater.logicalanvil.mixin;

import archives.tater.logicalanvil.LogicalAnvil;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(AnvilScreen.class)
public class AnvilScreenMixin {
    @ModifyConstant(
            method = "drawForeground",
            constant = @Constant(intValue = 40)
    )
    public int removeCap2(int constant) {
        return LogicalAnvil.TOO_EXPENSIVE_SIGNAL;
    }
}
