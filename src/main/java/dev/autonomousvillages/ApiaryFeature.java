package dev.autonomousvillages;

/** Пасека: ульи у цветников, мёд к столу. Placeholder: switch {@code apiary} in {@link ModConfig}, state in {@link VillageState#features}. */
final class ApiaryFeature implements Features.Feature {
    @Override public String key(){return "apiary";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.apiary;}
}
