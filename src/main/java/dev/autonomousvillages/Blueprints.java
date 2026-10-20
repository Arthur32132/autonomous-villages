package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
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
        /** Height of the lowest door above the template's floor layer; 1 for a door standing on the floor. */
        int doorLevel(){
            return blocks.stream().filter(e->e.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                    .mapToInt(e->e.pos().getY()).min().orElse(1);
        }
    }
    private static final Map<Identifier,Optional<Template>> CACHE=new HashMap<>();

    /** Vanilla village style for a biome-based villager type. */
    static String style(String villagerType){
        return switch(villagerType){case "desert"->"desert";case "savanna"->"savanna";case "snow"->"snowy";case "taiga"->"taiga";default->"plains";};
    }
    /**
     * The village's workplaces of the style: fields, library, temple, smiths, tannery, butcher, armorer,
     * mason, shepherd, fletcher... (vanilla templates without beds; pens, stables and the meeting place left out).
     */
    static List<Template> civic(ServerLevel level,String style){
        return workplaces(templates(level,style).stream().filter(id->workshop(name(id))).map(id->load(level,id)).flatMap(Optional::stream).toList(),Blueprints::scaffolding);
    }
    /** Workplaces of a size and cost the village takes on; a trade's designs that need scaffolding only when it has no other. */
    static List<Template> workplaces(List<Template> designs,java.util.function.Predicate<Template> scaffolding){
        var all=designs.stream().filter(t->t.beds()==0&&t.size().getX()<=13&&t.size().getZ()<=13&&t.work()<=360&&(t.work()>=40||t.id().getPath().contains("farm"))).toList();
        var reachable=new HashSet<String>();for(var t:all)if(!scaffolding.test(t))reachable.add(trade(name(t.id())));
        return all.stream().filter(t->!scaffolding.test(t)||!reachable.contains(trade(name(t.id())))).toList();
    }
    /** A profession's workplace by its template name: not a dwelling, pen, stable, meeting place or street accessory. */
    static boolean workshop(String name){
        return !name.matches(".*_(small|medium|big)_house(_\\d+)?")&&!name.contains("animal_pen")&&!name.contains("meeting")
                &&!name.contains("accessory")&&!name.contains("stable");
    }
    /** "plains_tool_smith_1" → "tool_smith"; "plains_masons_house_1" and "desert_mason_1" → "mason". */
    static String trade(String design){
        String n=design.replaceAll("_\\d+$","");int cut=n.indexOf('_');
        n=cut<0?n:n.substring(cut+1);
        return n.replaceAll("_(house|cottage)$","").replaceAll("^(mason|shepherd|butcher)s","$1").replace("weapon_smith","weaponsmith");
    }
    /** Houses with beds of one style (small, medium and big), smallest first. */
    static List<Template> houses(ServerLevel level,String style){
        return dwellings(templates(level,style).stream().filter(id->id.getPath().contains("_house_")||id.getPath().contains("_cottage_"))
                .map(id->load(level,id)).flatMap(Optional::stream).toList(),Blueprints::scaffolding);
    }
    /** Houses with beds that fit a site; those that need scaffolding only when the style has no other. */
    static List<Template> dwellings(List<Template> designs,java.util.function.Predicate<Template> scaffolding){
        var all=designs.stream().filter(t->t.beds()>0&&t.size().getX()<=12&&t.size().getZ()<=12)
                .sorted(Comparator.comparingInt(t->t.size().getX()*t.size().getZ())).toList();
        var reachable=all.stream().filter(t->!scaffolding.test(t)).toList();
        return reachable.isEmpty()?all:reachable;
    }
    static String name(Identifier id){String p=id.getPath();return p.substring(p.lastIndexOf('/')+1);}

    /**
     * Template ids of each style's houses folder, listed once per structure manager: listing rescans every
     * data pack, and the planners ask every 100 ticks while they search for a site.
     */
    static final class Listing {
        private java.lang.ref.WeakReference<Object> owner=new java.lang.ref.WeakReference<>(null);
        private Map<String,List<Identifier>> byStyle=Map.of();
        List<Identifier> of(Object source,String style,java.util.function.Supplier<java.util.stream.Stream<Identifier>> all){
            if(owner.get()!=source){byStyle=group(all.get());owner=new java.lang.ref.WeakReference<>(source);}
            return byStyle.getOrDefault(style,List.of());
        }
        boolean fresh(Object source){return owner.get()==source;}
        void forget(){owner=new java.lang.ref.WeakReference<>(null);byStyle=Map.of();}
    }
    /** {@code minecraft:village/<style>/houses/<name>} ids by style, sorted. */
    static Map<String,List<Identifier>> group(java.util.stream.Stream<Identifier> ids){
        var out=new HashMap<String,List<Identifier>>();
        ids.filter(id->id.getNamespace().equals("minecraft")).forEach(id->{
            String[] part=id.getPath().split("/");
            if(part.length==4&&part[0].equals("village")&&part[2].equals("houses"))out.computeIfAbsent(part[1],k->new ArrayList<>()).add(id);
        });
        out.replaceAll((k,list)->list.stream().sorted(Comparator.comparing(Identifier::toString)).toList());
        return out;
    }
    private static final Listing LISTING=new Listing();
    private static boolean hooked;
    static List<Identifier> templates(ServerLevel level,String style){
        if(!hooked){hooked=true;net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server,resources,ok)->forget());}
        var manager=level.getStructureManager();
        // Another server (a new world in the same game) has its own templates.
        if(!LISTING.fresh(manager)){CACHE.clear();SCAFFOLD.clear();}
        return LISTING.of(manager,style,manager::listTemplates);
    }
    /** A data pack reload may change the templates: list and parse them afresh. */
    static void forget(){LISTING.forget();CACHE.clear();SCAFFOLD.clear();}
    static Optional<Template> load(ServerLevel level,Identifier id){
        return CACHE.computeIfAbsent(id,key->{
            try{return level.getStructureManager().get(key).map(t->parse(level.registryAccess().lookupOrThrow(Registries.BLOCK),key,t.getSize(),t.save(new CompoundTag())));}
            catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Cannot read village template {}",key,broken);return Optional.empty();}
        });
    }
    static Template parse(HolderLookup<Block> blocks,Identifier id,Vec3i size,CompoundTag nbt){
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

    // --- builder reach ----------------------------------------------------------------------------
    /** BuildJob's reach (4.2) less Village.stand's margin: the builder's eye must be this close to the block's centre. */
    static final double REACH=4.2-.65,EYE=1.62;
    /**
     * Structural cells no builder stand reaches, and structural blocks that hang on or stand on a cell the
     * plan leaves to decoration or to nothing (they wait for a support the village may never place).
     */
    record Audit(Identifier id,int structural,List<BlockPos> unreachable,List<BlockPos> unsupported){
        /** The house would wait for scaffolding the village never puts up. */
        boolean needsScaffolding(){return !unreachable.isEmpty();}
    }
    private static final Map<Identifier,Boolean> SCAFFOLD=new HashMap<>();
    static boolean scaffolding(Template t){
        return SCAFFOLD.computeIfAbsent(t.id(),k->{
            try{return audit(t).needsScaffolding();}
            catch(RuntimeException broken){AutonomousVillagesMod.LOG.warn("Cannot audit village template {}",k,broken);return false;}
        });
    }
    /**
     * Builds bottom-up as BuildJob does: while layer y goes up, the layers below stand and the ground
     * around the house is at the door's level. A builder walks (steps of one, drops of three, ladders)
     * from the street to a stand Village.stand would accept, and places a block if his eye is within
     * {@link #REACH} of its centre.
     */
    static Audit audit(Template t){
        var plan=new HashMap<BlockPos,BlockState>();for(var e:t.blocks())plan.put(e.pos(),e.state());
        var grid=new Grid(plan,new HashSet<>(t.clear()),t.size(),t.doorLevel());
        var walks=new HashMap<Integer,Set<BlockPos>>();
        var unreachable=new ArrayList<BlockPos>();var unsupported=new ArrayList<BlockPos>();int structural=0;
        for(var e:t.blocks()){
            BlockState s=e.state();BlockPos c=e.pos();
            if(!structural(s))continue;
            structural++;
            var support=support(c,s);
            if(support.isPresent()){var under=plan.get(support.get());if(under==null?!grid.ground(support.get()):!structural(under))unsupported.add(c);}
            // The upper half of a door and the head of a bed come with the other half.
            if(s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)&&s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                    ||s.getBlock() instanceof BedBlock&&s.getValue(BedBlock.PART)==net.minecraft.world.level.block.state.properties.BedPart.HEAD)continue;
            var walk=walks.computeIfAbsent(c.getY(),grid::walk);
            if(!grid.reachable(c,walk))unreachable.add(c);
        }
        return new Audit(t.id(),structural,List.copyOf(unreachable),List.copyOf(unsupported));
    }
    /** The plan as it stands while one layer is built: the layers below it and the ground. */
    private record Grid(Map<BlockPos,BlockState> plan,Set<BlockPos> air,Vec3i size,int street){
        boolean ground(BlockPos p){return !air.contains(p)&&p.getY()<street;}
        BlockState built(BlockPos p,int layer){return p.getY()<layer?plan.get(p):null;}
        boolean solid(BlockPos p,int layer){
            var s=built(p,layer);
            if(s==null)return ground(p);
            return !(s.getBlock() instanceof DoorBlock)&&!s.is(BlockTags.CLIMBABLE)&&!s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,p).isEmpty();
        }
        boolean climbable(BlockPos p,int layer){var s=built(p,layer);return s!=null&&s.is(BlockTags.CLIMBABLE);}
        /** Village.safeFeet: feet and head free on a floor half a block to a block high (not a carpet, not a fence top). */
        boolean stand(BlockPos p,int layer){
            if(solid(p,layer)||solid(p.above(),layer))return false;
            BlockPos below=p.below();
            if(!solid(below,layer))return false;
            var s=built(below,layer);if(s==null)return true;
            double top=s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,below).max(Direction.Axis.Y);
            return top>=.5&&top<=1;
        }
        /** A cell a builder passes through: a stand, or a ladder he climbs (but cannot work from). */
        boolean node(BlockPos p,int layer){return stand(p,layer)||climbable(p,layer)&&!solid(p.above(),layer);}
        double feet(BlockPos p,int layer){
            var s=built(p.below(),layer);if(s==null)return p.getY();
            var shape=s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,p.below());
            return shape.isEmpty()?p.getY():p.getY()+Math.min(0,shape.max(Direction.Axis.Y)-1);
        }
        boolean inside(BlockPos p){return p.getX()>=-3&&p.getX()<size.getX()+3&&p.getZ()>=-3&&p.getZ()<size.getZ()+3&&p.getY()>=-1&&p.getY()<=size.getY()+2;}
        /** Cells a builder walks or climbs to from the street while {@code layer} is built. */
        Set<BlockPos> walk(int layer){
            var seen=new HashSet<BlockPos>();var open=new ArrayDeque<BlockPos>();
            for(int x=-3;x<size.getX()+3;x++)for(int z=-3;z<size.getZ()+3;z++){
                BlockPos p=new BlockPos(x,street,z);
                if((x<0||z<0||x>=size.getX()||z>=size.getZ())&&stand(p,layer)&&seen.add(p))open.add(p);
            }
            while(!open.isEmpty()){
                BlockPos s=open.poll();var next=new ArrayList<BlockPos>();
                for(Direction d:Direction.Plane.HORIZONTAL){
                    BlockPos n=s.relative(d);
                    if(node(n,layer)){next.add(n);continue;}
                    if(!solid(s.above(2),layer)&&node(n.above(),layer)){next.add(n.above());continue;}
                    if(solid(n,layer)||solid(n.above(),layer))continue;
                    for(int k=1;k<=3;k++){BlockPos q=n.below(k);if(solid(q,layer))break;if(node(q,layer)){next.add(q);break;}}
                }
                if(climbable(s,layer)&&node(s.above(),layer))next.add(s.above());
                if(climbable(s.below(),layer)&&node(s.below(),layer))next.add(s.below());
                for(BlockPos q:next)if(inside(q)&&seen.add(q))open.add(q);
            }
            return seen;
        }
        /** Village.stand's search: up to two cells aside, six below or one above, eye within reach of the centre. */
        boolean reachable(BlockPos c,Set<BlockPos> walk){
            double cx=c.getX()+.5,cy=c.getY()+.5,cz=c.getZ()+.5;
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-6;dy<=1;dy++){
                BlockPos p=c.offset(dx,dy,dz);
                if(p.equals(c)||p.above().equals(c)||p.below().equals(c)||!walk.contains(p)||!stand(p,c.getY()))continue;
                double ex=p.getX()+.5-cx,ey=feet(p,c.getY())+EYE-cy,ez=p.getZ()+.5-cz;
                if(ex*ex+ey*ey+ez*ez<=REACH*REACH)return true;
            }
            return false;
        }
    }
    /** The cell a block hangs on or stands on, for the blocks that drop without one. */
    static Optional<BlockPos> support(BlockPos p,BlockState s){
        Block b=s.getBlock();
        if(b instanceof WallTorchBlock||b instanceof RedstoneWallTorchBlock||b instanceof LadderBlock||b instanceof WallSignBlock||b instanceof WallBannerBlock)
            return Optional.of(p.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()));
        if(b instanceof FaceAttachedHorizontalDirectionalBlock)return Optional.of(switch(s.getValue(BlockStateProperties.ATTACH_FACE)){
            case FLOOR->p.below();case CEILING->p.above();default->p.relative(s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());});
        if(b instanceof LanternBlock)return Optional.of(s.getValue(LanternBlock.HANGING)?p.above():p.below());
        if(b instanceof CeilingHangingSignBlock)return Optional.of(p.above());
        if(b instanceof BaseTorchBlock||b instanceof CarpetBlock||b instanceof BasePressurePlateBlock||b instanceof BaseRailBlock||b instanceof StandingSignBlock
                ||b instanceof BannerBlock||b instanceof VegetationBlock||b instanceof SnowLayerBlock||b instanceof AbstractCandleBlock
                ||b instanceof DoorBlock&&s.getValue(DoorBlock.HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)return Optional.of(p.below());
        return Optional.empty();
    }
}
