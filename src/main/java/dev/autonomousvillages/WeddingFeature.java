package dev.autonomousvillages;

/** Свадьбы: праздник у колокола перед рождением малыша. Placeholder: switch {@code weddings} in {@link ModConfig}, state in {@link VillageState#features}. */
final class WeddingFeature implements Features.Feature {
    @Override public String key(){return "weddings";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.weddings;}
}
