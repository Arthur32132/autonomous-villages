package dev.autonomousvillages;

/** Мастерство: звания жителей за долгую работу одним ремеслом. Placeholder: switch {@code mastery} in {@link ModConfig}, state in {@link VillageState#features}. */
final class MasteryFeature implements Features.Feature {
    @Override public String key(){return "mastery";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.mastery;}
}
