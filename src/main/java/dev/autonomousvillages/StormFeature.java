package dev.autonomousvillages;

/** Гроза: жители уходят под крышу, на вышке громоотвод. Placeholder: switch {@code storm} in {@link ModConfig}, state in {@link VillageState#features}. */
final class StormFeature implements Features.Feature {
    @Override public String key(){return "storm";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.storm;}
}
