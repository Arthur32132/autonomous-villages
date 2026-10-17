package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Explicit human boundaries. All mutation paths, including library mining, consult this policy. */
final class VillagePolicy {
    static final class Zone {
        String activity="all";
        int minX,minZ,maxX,maxZ;
        boolean contains(BlockPos p){return p.getX()>=minX&&p.getX()<=maxX&&p.getZ()>=minZ&&p.getZ()<=maxZ;}
    }
    List<Zone> zones=new ArrayList<>();
    Set<Long> privateChests=new LinkedHashSet<>();
    Set<String> expansion=new LinkedHashSet<>(List.of("north","south","east","west"));
    boolean allows(String activity,BlockPos p){if(activity.equals("none"))return true;return zones.stream().noneMatch(z->z.contains(p)&&(z.activity.equals("all")||z.activity.equals(activity)));}
    boolean isPrivate(Village v,BlockPos p){
        if(privateChests.contains(p.asLong()))return true;
        var s=v.level.getBlockState(p);
        return s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock&&s.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                &&privateChests.contains(p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(s)).asLong());
    }
    boolean expansionAllowed(VillageState.Bounds old,VillageState.Bounds next){
        return (next.minX>=old.minX||expansion.contains("west"))&&(next.maxX<=old.maxX||expansion.contains("east"))
                &&(next.minZ>=old.minZ||expansion.contains("north"))&&(next.maxZ<=old.maxZ||expansion.contains("south"));
    }
    void validate(){
        if(zones==null||zones.size()>64||privateChests==null||privateChests.size()>128||privateChests.contains(null)||expansion==null
                ||!Set.of("north","south","east","west").containsAll(expansion))throw new IllegalArgumentException("Повреждённые границы вмешательства");
        for(var z:zones)if(z==null||!Set.of("all","wood","mine","build","farm").contains(z.activity)||z.minX>z.maxX||z.minZ>z.maxZ
                ||Math.abs((long)z.minX)>=29999000||Math.abs((long)z.maxX)>=29999000||Math.abs((long)z.minZ)>=29999000||Math.abs((long)z.maxZ)>=29999000)
            throw new IllegalArgumentException("Повреждённый запрещённый участок");
    }
}
