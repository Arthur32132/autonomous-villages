package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Vanilla village houses ({@code minecraft:village/<biome>/houses/*}) as building plans. Each block
 * is either structural (walls, floor, roof, door, beds, light: the village can make it) or decoration
 * (glass, flower pots, workstations): decoration is placed only when the store happens to hold it.
 */
final class Blueprints {
    private Blueprints(){}
    record Entry(BlockPos pos,BlockState state){}
    /** Blocks relative to the template origin; {@code front} is the side the entrance faces. */
    record Template(Identifier id,Vec3i size,List<Entry> blocks,List<BlockPos> clear,Direction front,int beds){
        /** Blocks the village has to make; decoration does not count. */
        int work(){return (int)blocks.stream().filter(e->structural(e.state())).count();}
        /** Beds per hundred structural blocks: how much growth the house buys. */
        double value(){return beds*100.0/Math.max(1,work());}
    }
    private static final Map<Identifier,Optional<Template>> CACHE=new HashMap<>();

    /** Vanilla village style for a biome-based villager type. */
    static String style(String villagerType){
        return switch(villagerType){case "desert"->"desert";case "savanna"->"savanna";case "snow"->"snowy";case "taiga"->"taiga";default->"plains";};
    }
    /** Houses with beds of one style (small, medium and big), smallest first. */
    static List<Template> houses(ServerLevel level,String style){
        String prefix="village/"+style+"/houses/";
        return level.getStructureManager().listTemplates()
                .filter(id->id.getNamespace().equals("minecraft")&&id.getPath().startsWith(prefix)&&(id.getPath().contains("_house_")||id.getPath().contains("_cottage_")))
                .sorted(Comparator.comparing(Identifier::toString))
                .map(id->load(level,id)).flatMap(Optional::stream).filter(t->t.beds()>0&&t.size().getX()<=12&&t.size().getZ()<=12)
                .sorted(Comparator.comparingInt(t->t.size().getX()*t.size().getZ())).toList();
    }
    static Optional<Template> load(ServerLevel level,Identifier id){
        return CACHE.computeIfAbsent(id,key->{
            try{return level.getStructureManager().get(key).map(t->parse(level,key,t.getSize(),t.save(new CompoundTag())));}
            catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Cannot read village template {}",key,broken);return Optional.empty();}
        });
    }
    private static Template parse(ServerLevel level,Identifier id,Vec3i size,CompoundTag nbt){
        var blocks=level.registryAccess().lookupOrThrow(Registries.BLOCK);
        var paletteTag=nbt.getListOrEmpty("palette");
        if(paletteTag.isEmpty())paletteTag=nbt.getListOrEmpty("palettes").getListOrEmpty(0);
        var palette=new ArrayList<BlockState>();
        for(int i=0;i<paletteTag.size();i++)palette.add(NbtUtils.readBlockState(blocks,paletteTag.getCompoundOrEmpty(i)));
        var out=new ArrayList<Entry>();var clear=new ArrayList<BlockPos>();Direction front=Direction.NORTH;int beds=0;
        var list=nbt.getListOrEmpty("blocks");
        for(int i=0;i<list.size();i++){
            var entry=list.getCompoundOrEmpty(i);
            var pos=entry.getListOrEmpty("pos");
            BlockPos p=new BlockPos(pos.getIntOr(0,0),pos.getIntOr(1,0),pos.getIntOr(2,0));
            BlockState state=palette.get(entry.getIntOr("state",0));
            if(state.is(Blocks.STRUCTURE_VOID))continue;
            if(state.is(Blocks.JIGSAW)){
                var data=entry.getCompoundOrEmpty("nbt");
                if(data.getStringOr("name","").equals("minecraft:building_entrance"))front=state.getValue(BlockStateProperties.ORIENTATION).front();
                // The jigsaw stands in for its final block: a doorstep, a floor plank, or air.
                try{state=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(blocks,data.getStringOr("final_state","minecraft:air"),false).blockState();}
                catch(Exception malformed){state=Blocks.AIR.defaultBlockState();}
            }
            if(state.isAir()){clear.add(p);continue;}
            if(state.getBlock() instanceof BedBlock&&state.getValue(BedBlock.PART)==net.minecraft.world.level.block.state.properties.BedPart.FOOT)beds++;
            out.add(new Entry(p,state));
        }
        return new Template(id,size,List.copyOf(out),List.copyOf(clear),front,beds);
    }
    /** Rotation that turns the template's entrance toward a direction. */
    static Rotation facing(Direction from,Direction to){
        for(Rotation r:Rotation.values())if(r.rotate(from)==to)return r;
        return Rotation.NONE;
    }
    /** Template coordinates turned in place; the result stays inside the rotated footprint. */
    static BlockPos turn(BlockPos p,Vec3i size,Rotation r){
        return switch(r){
            case NONE->p;
            case CLOCKWISE_90->new BlockPos(size.getZ()-1-p.getZ(),p.getY(),p.getX());
            case CLOCKWISE_180->new BlockPos(size.getX()-1-p.getX(),p.getY(),size.getZ()-1-p.getZ());
            case COUNTERCLOCKWISE_90->new BlockPos(p.getZ(),p.getY(),size.getX()-1-p.getX());
        };
    }
    static Vec3i footprint(Vec3i size,Rotation r){return r==Rotation.CLOCKWISE_90||r==Rotation.COUNTERCLOCKWISE_90?new Vec3i(size.getZ(),size.getY(),size.getX()):size;}

    /** True for blocks the village can make or substitute: wood, cobblestone family, doors, beds, light, store blocks. */
    static boolean structural(BlockState s){
        if(VillageIndex.naturalSoil(s)||s.is(Blocks.DIRT_PATH)||s.is(Blocks.FARMLAND))return false;
        return s.is(BlockTags.PLANKS)||s.is(BlockTags.LOGS)||s.is(BlockTags.WOODEN_STAIRS)||s.is(BlockTags.WOODEN_SLABS)||s.is(BlockTags.WOODEN_FENCES)
                ||s.is(BlockTags.WOODEN_DOORS)||s.is(BlockTags.WOODEN_TRAPDOORS)||s.getBlock() instanceof BedBlock||s.is(Blocks.TORCH)||s.is(Blocks.WALL_TORCH)
                ||s.is(Blocks.COBBLESTONE)||Construction.masonry(s)||stone(s)||Construction.stoneStairs(s)||Construction.stoneSlab(s)||s.is(BlockTags.WALLS)
                ||s.is(Blocks.GLASS_PANE)||s.is(Blocks.LADDER)||s.is(Blocks.CHEST)||s.is(Blocks.CRAFTING_TABLE)||s.is(Blocks.FURNACE);
    }
    private static boolean stone(BlockState s){
        return s.is(Blocks.STONE)||s.is(Blocks.SMOOTH_STONE)||s.is(Blocks.DIORITE)||s.is(Blocks.ANDESITE)||s.is(Blocks.GRANITE)
                ||s.is(Blocks.POLISHED_DIORITE)||s.is(Blocks.POLISHED_ANDESITE)||s.is(Blocks.POLISHED_GRANITE);
    }
    /** Cobblestone stands in for the stone, sandstone, terracotta and brick of other biomes, keeping each block's shape. */
    static BlockState buildable(BlockState s){
        if(Construction.stoneStairs(s)&&!s.is(Blocks.COBBLESTONE_STAIRS))return reshape(s,Blocks.COBBLESTONE_STAIRS);
        if(Construction.stoneSlab(s)&&!s.is(Blocks.COBBLESTONE_SLAB))return reshape(s,Blocks.COBBLESTONE_SLAB);
        if(s.is(BlockTags.WALLS)&&!s.is(Blocks.COBBLESTONE_WALL))return reshape(s,Blocks.COBBLESTONE_WALL);
        if(stone(s)||Construction.masonry(s))return Blocks.COBBLESTONE.defaultBlockState();
        return s;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static BlockState reshape(BlockState from,Block to){
        BlockState out=to.defaultBlockState();
        for(var property:from.getProperties())if(out.hasProperty(property))out=out.setValue((net.minecraft.world.level.block.state.properties.Property)property,(Comparable)from.getValue(property));
        return out;
    }
}
