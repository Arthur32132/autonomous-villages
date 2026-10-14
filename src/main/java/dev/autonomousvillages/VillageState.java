package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Only metadata lives here; every item stays in a vanilla chest or entity inventory. */
public final class VillageState {
    public String name, owner;
    public int x, y, z, radius;
    public boolean active = true;
    public Set<UUID> residents = new LinkedHashSet<>();
    public Set<Long> storage = new LinkedHashSet<>();
    public Set<Long> furnaces = new LinkedHashSet<>();
    public Set<Long> plantedTrees = new LinkedHashSet<>();
    public Set<Long> plantedCrops = new LinkedHashSet<>();
    public Set<Long> fellingTrees = new LinkedHashSet<>();
    public Long shelter;
    public Long mineEntrance;
    public int mineDirection, mineLength;
    public int completedShelters;
    public VillageState() { }
    public VillageState(String name, UUID owner, BlockPos center, int radius) {
        this.name = name; this.owner = owner.toString(); this.x = center.getX(); this.y = center.getY();
        this.z = center.getZ(); this.radius = radius;
    }
    public BlockPos center() { return new BlockPos(x, y, z); }
    public boolean contains(BlockPos pos) {
        long dx = (long)pos.getX() - x, dz = (long)pos.getZ() - z;
        return dx * dx + dz * dz <= (long)radius * radius && Math.abs(pos.getY() - y) <= 48;
    }
    public boolean overlaps(VillageState other) {
        long dx = (long)x - other.x, dz = (long)z - other.z, r = radius + other.radius + 8L;
        return dx * dx + dz * dz <= r * r;
    }
    public void validate() {
        if (name == null || !name.matches("[A-Za-zА-Яа-яЁё0-9_-]{1,32}") || owner == null
                || radius < 24 || radius > 64 || Math.abs((long)x) >= 29_999_000L || Math.abs((long)z) >= 29_999_000L
                || y < -64 || y > 320 || residents == null || residents.size() > 64 || storage == null || storage.size() > 16
                || furnaces == null || furnaces.size() > 16 || plantedTrees == null || plantedTrees.size() > 512
                || plantedCrops == null || plantedCrops.size() > 512 || completedShelters < 0 || completedShelters > 8
                || mineLength < 0 || mineLength > 32 || mineDirection < 0 || mineDirection > 3 || fellingTrees==null || fellingTrees.size()>128)
            throw new IllegalArgumentException("Повреждённые данные деревни");
        UUID.fromString(owner);
        for (Long pos : storage) if (pos == null || !contains(BlockPos.of(pos))) throw new IllegalArgumentException("Склад вне деревни");
        for (Long pos : furnaces) if (pos == null || !contains(BlockPos.of(pos))) throw new IllegalArgumentException("Печь вне деревни");
        for(Set<Long> positions:List.of(plantedTrees,plantedCrops,fellingTrees))for(Long pos:positions)
            if(pos==null||!contains(BlockPos.of(pos)))throw new IllegalArgumentException("Рабочая область вне деревни");
        if (shelter != null && !contains(BlockPos.of(shelter))) throw new IllegalArgumentException("Стройка вне деревни");
        if (mineEntrance != null && !contains(BlockPos.of(mineEntrance))) throw new IllegalArgumentException("Шахта вне деревни");
    }
}
