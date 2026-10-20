package dev.autonomousvillages;

/** Скотный двор: куры и коровы, яйца и мясо к столу. Placeholder: switch {@code livestock} in {@link ModConfig}, state in {@link VillageState#features}. */
final class LivestockFeature implements Features.Feature {
    @Override public String key(){return "livestock";}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.livestock;}
}
