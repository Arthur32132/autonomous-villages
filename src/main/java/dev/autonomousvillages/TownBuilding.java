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
    public String kind="house";
    public long origin;
    public int tier;
    public boolean completed;
    public List<Plan> blocks=new ArrayList<>();
    public List<Long> cuts=new ArrayList<>();
    public List<Plan> foundation=new ArrayList<>();
    public Long approach;
    /** Actual feet cells, including unbuilt ground; empty for legacy road metadata. */
    public List<Long> routeCells=new ArrayList<>();
    public Long routeStart;
    transient long routeCheckedTick=Long.MIN_VALUE;
    transient boolean routeReady;
    public Long housingFor;
    public static final class Plan {
        public long pos;
        public String state;
        public boolean replaceOwned;
        private transient BlockState decoded;
        public Plan() { }
        Plan(BlockPos p,BlockState b){pos=p.asLong();state=BlockState.CODEC.encodeStart(JsonOps.INSTANCE,b).getOrThrow().toString();decoded=b;}
        public BlockPos position(){return BlockPos.of(pos);}
        public BlockState block(){if(decoded==null)decoded=BlockState.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(state)).getOrThrow();return decoded;}
        void decodedReset(){decoded=null;}
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
    int built(Village v){return (int)blocks.stream().filter(p->p.matches(v)).count();}
    void validate(VillageState village){
        if(routeCells==null||routeCells.size()>NetworkRoutes.MAX_LENGTH+1||routeCells.stream().anyMatch(p->p==null||!village.contains(BlockPos.of(p)))
                ||routeStart!=null&&!village.contains(BlockPos.of(routeStart))
                ||!routeCells.isEmpty()&&(!"road".equals(kind)||routeStart==null||routeCells.getFirst().longValue()!=routeStart
                    ||routeCells.getLast().longValue()!=origin))throw new IllegalArgumentException("Повреждённый маршрут здания");
        for(int i=1;i<routeCells.size();i++){
            var a=BlockPos.of(routeCells.get(i-1));var b=BlockPos.of(routeCells.get(i));
            if(Math.abs(a.getX()-b.getX())+Math.abs(a.getZ()-b.getZ())!=1||Math.abs(a.getY()-b.getY())>1)
                throw new IllegalArgumentException("Разрыв сохранённого маршрута");
        }
        if(kind==null||!Set.of("house","warehouse","native_house","sheep_pen","field","road","lumber_yard","smithy","granary","watch_post").contains(kind)||blocks==null||blocks.size()>4096||tier<0||tier>3
                ||!village.contains(BlockPos.of(origin)))throw new IllegalArgumentException("Повреждённый проект здания");
        if(cuts==null||cuts.size()>64||cuts.stream().anyMatch(p->p==null||!village.contains(BlockPos.of(p)))||foundation==null||foundation.size()>64
                ||approach!=null&&!village.contains(BlockPos.of(approach))||housingFor!=null&&!village.contains(BlockPos.of(housingFor)))throw new IllegalArgumentException("Повреждённая подготовка площадки");
        var all=new ArrayList<>(blocks);all.addAll(foundation);
        for(Plan p:all)if(p==null||p.state==null||p.state.length()>2048||!village.contains(p.position())||p.block().getBlock().asItem()==Items.AIR
                ||p.block().getBlock() instanceof DoorBlock&&!village.contains(p.position().above())
                ||p.block().getBlock() instanceof BedBlock&&!village.contains(p.position().relative(p.block().getValue(BedBlock.FACING))))
            throw new IllegalArgumentException("Повреждённая деталь здания");
    }
}
