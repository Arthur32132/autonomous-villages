package dev.autonomousvillages;

/** Герб деревни: флаги на вышке, амбаре и воротах. Placeholder: switch {@code heraldry} in {@link ModConfig}, state in {@link VillageState#features}. */
final class HeraldryFeature implements Features.Feature {
    @Override public String key(){return "heraldry";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.heraldry;}
}
