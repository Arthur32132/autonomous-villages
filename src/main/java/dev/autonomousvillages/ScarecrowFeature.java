package dev.autonomousvillages;

/** Пугала на полях из тыквы, забора и сена. Placeholder: switch {@code scarecrows} in {@link ModConfig}, state in {@link VillageState#features}. */
final class ScarecrowFeature implements Features.Feature {
    @Override public String key(){return "scarecrows";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.scarecrows;}
}
