package dev.autonomousvillages.mixin;

import dev.autonomousvillages.AutonomousVillagesMod;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chunk tickets alone cannot keep ticking when a dedicated server auto-pauses with zero players. */
@Mixin(MinecraftServer.class)
public abstract class ServerEmptyPauseMixin {
    @Shadow private int emptyTicks;
    @Inject(method="tickServer",at=@At("HEAD"))
    private void autonomousVillages$keepSettlementTime(BooleanSupplier haveTime,CallbackInfo ci){
        if(AutonomousVillagesMod.hasActiveVillages())emptyTicks=0;
    }
}
