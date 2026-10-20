package dev.autonomousvillages;

/** Вечерние посиделки у костра на площади. Placeholder: switch {@code campfire} in {@link ModConfig}, state in {@link VillageState#features}. */
final class CampfireFeature implements Features.Feature {
    @Override public String key(){return "campfire";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.campfire;}
}
