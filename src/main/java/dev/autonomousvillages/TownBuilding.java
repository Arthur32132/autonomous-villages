package dev.autonomousvillages;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Persistent architectural intent. Inventories and building progress remain in the actual world. */
public final class TownBuilding {
    static final Set<String> KINDS=Set.of("house","native_house","doors","well","lamp","path","workshop","farm_plot","tower","civic","storehouse","forge","market","tavern","mill","dock","wall","chapel","stable","pen");
    public String kind="house";
    public long origin;
    public boolean completed;
    public List<Plan> blocks=new ArrayList<>();
    public List<Long> cuts=new ArrayList<>();
    /** Vanilla template the plan was made from, e.g. "plains_small_house_1"; empty for the mod's own designs. */
    public String design="";
    /** Ground cells of the footpath from this building's door toward the village centre. */
    public List<Long> path=new ArrayList<>();
    public static final class Plan {
        public long pos;
        public String state;
        /** Decoration: placed only when the store holds the exact item; never holds up completion. */
        public boolean optional;
        private transient BlockState decoded;
        public Plan() { }
        Plan(BlockPos p,BlockState b){pos=p.asLong();state=BlockState.CODEC.encodeStart(JsonOps.INSTANCE,b).getOrThrow().toString();decoded=b;}
        public BlockPos position(){return BlockPos.of(pos);}
        public BlockState block(){if(decoded==null)decoded=BlockState.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(state)).getOrThrow();return decoded;}
        Construction.Step step(){return new Construction.Step(position(),block(),block().getBlock().asItem());}
        boolean matches(Village v){
            BlockState expected=block(),actual=v.level.getBlockState(position());
            if(!Construction.matches(step(),actual))return false;
            if(expected.getBlock() instanceof DoorBlock)return Construction.matches(new Construction.Step(position().above(),expected.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER),step().item()),v.level.getBlockState(position().above()));
            if(expected.getBlock() instanceof BedBlock)return Construction.matches(new Construction.Step(position().relative(expected.getValue(BedBlock.FACING)),expected.setValue(BedBlock.PART,BedPart.HEAD),step().item()),v.level.getBlockState(position().relative(expected.getValue(BedBlock.FACING))));
            return true;
        }
    }
    public TownBuilding() { }
    TownBuilding(String kind,BlockPos origin){this.kind=kind;this.origin=origin.asLong();}
    void add(BlockPos p,BlockState state){if(state.getBlock().asItem()!=Items.AIR)blocks.add(new Plan(p,state));}
    void decorate(BlockPos p,BlockState state){if(state.getBlock().asItem()!=Items.AIR){var plan=new Plan(p,state);plan.optional=true;blocks.add(plan);}}
    int built(Village v){return (int)blocks.stream().filter(p->p.matches(v)).count();}
    /** A building with a broken record is dropped on load rather than blocking the whole save. */
    boolean valid(VillageState village){
        if(design==null)design="";
        if(path==null)path=new ArrayList<>();
        path.removeIf(p->p==null||!village.contains(BlockPos.of(p)));
        if(path.size()>256)return false;
        if(kind==null||!KINDS.contains(kind)||blocks==null||blocks.size()>4096||cuts==null||cuts.size()>4096||design.length()>128||!village.contains(BlockPos.of(origin)))return false;
        cuts.removeIf(p->p==null||!village.contains(BlockPos.of(p)));
        try{
            for(Plan p:blocks)if(p==null||p.state==null||p.state.length()>2048||!village.contains(p.position())||p.block().getBlock().asItem()==Items.AIR)return false;
        }catch(RuntimeException malformed){return false;}
        return true;
    }
}
