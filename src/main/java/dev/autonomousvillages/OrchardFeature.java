package dev.autonomousvillages;

/** Фруктовый сад: яблони рядами у края деревни. Placeholder: switch {@code orchard} in {@link ModConfig}, state in {@link VillageState#features}. */
final class OrchardFeature implements Features.Feature {
    @Override public String key(){return "orchard";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.orchard;}
}
