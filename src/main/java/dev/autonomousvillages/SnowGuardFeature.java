package dev.autonomousvillages;

/** Снежные стражи: в снежной деревне лепят снеговиков-защитников. Placeholder: switch {@code snowGuards} in {@link ModConfig}, state in {@link VillageState#features}. */
final class SnowGuardFeature implements Features.Feature {
    @Override public String key(){return "snowGuards";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.snowGuards;}
}
