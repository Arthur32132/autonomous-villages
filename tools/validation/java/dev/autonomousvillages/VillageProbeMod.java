package dev.autonomousvillages;

public final class VillageProbeMod implements net.fabricmc.api.ModInitializer {
    @Override public void onInitialize() { VillageSelfTest.register(); }
}
