package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;

/** No cargo is serialized here: carried items stay in the actual residents' backpacks. */
public final class ExpeditionState {
    public String purpose="mine",phase="scout";
    public List<UUID> team=new ArrayList<>();
    public long target,started,phaseSince,deliveryStart;
    public int direction,length,completedSegments,brokenBlocks,attempts,harvested;
    public boolean departingTogether;
    public Set<UUID> unloadedMembers=new LinkedHashSet<>();
    public long mineralDeliveryStart,mineralCargoStart;
    public boolean cargoSnapshot;
    public long phaseBudget;
    public boolean dug;
    public int workedSegments,oreCollected;
    public int woolCollected;
    public int woolCargoStart;
    public boolean woolSnapshot;
    public long oreSince;
    public Set<Long> oreTargets=new LinkedHashSet<>();
    public Map<UUID,List<Long>> returnTrails=new LinkedHashMap<>();
    public ExpeditionState() { }
    void recordReturnPoint(UUID id,BlockPos point){
        var trail=returnTrails.computeIfAbsent(id,ignored->new ArrayList<>());
        int previous=trail.indexOf(point.asLong());
        if(previous>=0){trail.subList(previous+1,trail.size()).clear();return;}
        if(!trail.isEmpty()&&BlockPos.of(trail.getLast()).distSqr(point)<16)return;
        if(trail.size()==256)trail.removeFirst();trail.add(point.asLong());
    }
    public static final class Mine {
        public long entrance;
        public int direction,length;
        public boolean blocked;
        public Mine() { }
    }
    void validate(){
        if(purpose==null||phase==null||!Set.of("mine","wood","forage","wool").contains(purpose)||!Set.of("scout","prepare","rally","travel","work","return","unload","shelter").contains(phase)
                ||team==null||team.size()>4||team.stream().anyMatch(Objects::isNull)||length<0||length>64||completedSegments<0||completedSegments>64
                ||oreTargets==null||oreTargets.size()>128||oreTargets.stream().anyMatch(Objects::isNull)||workedSegments<0||workedSegments>length||oreCollected<0||oreCollected>24||oreSince<0||woolCollected<0||woolCargoStart<0
                ||returnTrails==null||returnTrails.size()>4||returnTrails.entrySet().stream().anyMatch(e->!team.contains(e.getKey())||e.getValue()==null||e.getValue().size()>256||e.getValue().stream().anyMatch(Objects::isNull))
                ||new HashSet<>(team).size()!=team.size()||unloadedMembers==null||!team.containsAll(unloadedMembers)||mineralDeliveryStart<0||mineralCargoStart<0||brokenBlocks<0||harvested<0||direction<0||direction>3||attempts<0||attempts>1000||Math.abs((long)BlockPos.of(target).getX())>=29_999_000L
                ||Math.abs((long)BlockPos.of(target).getZ())>=29_999_000L)throw new IllegalArgumentException("Повреждённая вылазка");
    }
}
