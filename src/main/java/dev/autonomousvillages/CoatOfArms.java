package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatterns;

/**
 * The village's coat of arms, derived from its name: a field of one of the sixteen dye colours and one or
 * two loom patterns that need no pattern item. Also the pure rules of the banner work: where a banner goes
 * on a plan, how much wool is spare and how many dyes the patterns take. No world access, so tests drive it.
 */
record CoatOfArms(DyeColor base,List<Layer> layers){
    /** One loom layer: a pattern in a colour. */
    record Layer(Charge charge,DyeColor colour){}
    /** A pattern the loom makes from a dye alone, with its Russian name and grammatical gender. */
    record Charge(ResourceKey<BannerPattern> key,String name,boolean feminine){}
    static final List<Charge> CHARGES=List.of(
            new Charge(BannerPatterns.BORDER,"кайма",true),new Charge(BannerPatterns.STRAIGHT_CROSS,"крест",false),
            new Charge(BannerPatterns.CROSS,"косой крест",false),new Charge(BannerPatterns.TRIANGLE_BOTTOM,"шеврон",false),
            new Charge(BannerPatterns.TRIANGLE_TOP,"опрокинутый шеврон",false),new Charge(BannerPatterns.STRIPE_CENTER,"столб",false),
            new Charge(BannerPatterns.STRIPE_MIDDLE,"пояс",false),new Charge(BannerPatterns.STRIPE_DOWNRIGHT,"перевязь",true),
            new Charge(BannerPatterns.CIRCLE_MIDDLE,"круг",false),new Charge(BannerPatterns.RHOMBUS_MIDDLE,"ромб",false),
            new Charge(BannerPatterns.HALF_HORIZONTAL,"верхняя половина",true),new Charge(BannerPatterns.STRIPE_TOP,"глава",true),
            new Charge(BannerPatterns.STRIPE_BOTTOM,"оконечность",true),new Charge(BannerPatterns.SQUARE_TOP_LEFT,"вольная часть",true));
    /** Six wool and a stick make a banner. */
    static final int WOOL_PER_BANNER=6;
    /** Wool for one bed stays in the store whatever the flag. */
    static final int KEEP_FOR_BEDS=3;
    static final int MAX_BANNERS=6;

    /**
     * Colours by how often arms carry them: every dye colour can come up, but mostly the old tinctures a
     * village can actually make, white from its sheep, yellow and red from dandelions and poppies, blue from cornflowers.
     */
    static final List<DyeColor> TINCTURES;
    static{
        var t=new ArrayList<DyeColor>();
        for(DyeColor c:DyeColor.values()){int n=switch(c){case WHITE->5;case YELLOW,RED->3;case BLUE->2;default->1;};for(int i=0;i<n;i++)t.add(c);}
        TINCTURES=List.copyOf(t);
    }
    /** The arms of a village called {@code name}: the same name always gives the same arms. */
    static CoatOfArms of(String name){
        long h=mix((name==null?"":name.strip().toLowerCase(Locale.ROOT)).hashCode());
        DyeColor base=TINCTURES.get((int)Math.floorMod(h,TINCTURES.size()));h=mix(h);
        int count=1+(int)Math.floorMod(h,2);h=mix(h);
        var layers=new ArrayList<Layer>();var used=new HashSet<Charge>();
        for(int tries=0;layers.size()<count&&tries<64;tries++){
            Charge charge=CHARGES.get((int)Math.floorMod(h,CHARGES.size()));h=mix(h);
            DyeColor colour=TINCTURES.get((int)Math.floorMod(h,TINCTURES.size()));h=mix(h);
            // A charge in the field's own colour would not show.
            if(colour!=base&&used.add(charge))layers.add(new Layer(charge,colour));
        }
        return new CoatOfArms(base,List.copyOf(layers));
    }
    private static long mix(long z){z+=0x9E3779B97F4A7C15L;z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;z=(z^(z>>>27))*0x94D049BB133111EBL;return z^(z>>>31);}

    private static final Map<DyeColor,String[]> NAMES=new EnumMap<>(Map.ofEntries(
            Map.entry(DyeColor.WHITE,new String[]{"белый","белая","белое"}),Map.entry(DyeColor.ORANGE,new String[]{"оранжевый","оранжевая","оранжевое"}),
            Map.entry(DyeColor.MAGENTA,new String[]{"пурпурный","пурпурная","пурпурное"}),Map.entry(DyeColor.LIGHT_BLUE,new String[]{"голубой","голубая","голубое"}),
            Map.entry(DyeColor.YELLOW,new String[]{"жёлтый","жёлтая","жёлтое"}),Map.entry(DyeColor.LIME,new String[]{"салатовый","салатовая","салатовое"}),
            Map.entry(DyeColor.PINK,new String[]{"розовый","розовая","розовое"}),Map.entry(DyeColor.GRAY,new String[]{"серый","серая","серое"}),
            Map.entry(DyeColor.LIGHT_GRAY,new String[]{"светло-серый","светло-серая","светло-серое"}),Map.entry(DyeColor.CYAN,new String[]{"бирюзовый","бирюзовая","бирюзовое"}),
            Map.entry(DyeColor.PURPLE,new String[]{"фиолетовый","фиолетовая","фиолетовое"}),Map.entry(DyeColor.BLUE,new String[]{"синий","синяя","синее"}),
            Map.entry(DyeColor.BROWN,new String[]{"коричневый","коричневая","коричневое"}),Map.entry(DyeColor.GREEN,new String[]{"зелёный","зелёная","зелёное"}),
            Map.entry(DyeColor.RED,new String[]{"красный","красная","красное"}),Map.entry(DyeColor.BLACK,new String[]{"чёрный","чёрная","чёрное"})));
    /** The colour as a Russian adjective: masculine, feminine or neuter. */
    static String colour(DyeColor c,int gender){return NAMES.get(c)[gender];}
    static final int MASCULINE=0,FEMININE=1,NEUTER=2;
    /** "синее поле, жёлтый крест, белая кайма". */
    String describe(){
        var parts=new ArrayList<String>();parts.add(colour(base,NEUTER)+" поле");
        for(var l:layers)parts.add(colour(l.colour(),l.charge().feminine()?FEMININE:MASCULINE)+" "+l.charge().name());
        return String.join(", ",parts);
    }

    // --- materials -------------------------------------------------------------------------------
    /** True when six wool of {@code colour} may go into a banner: no house waits for beds and wool for one bed of some colour is left. */
    static boolean woolFree(Map<DyeColor,Integer> wool,DyeColor colour,boolean bedsWaiting){
        int have=wool.getOrDefault(colour,0);
        if(bedsWaiting||have<WOOL_PER_BANNER)return false;
        int left=have-WOOL_PER_BANNER;
        for(var e:wool.entrySet())if(e.getKey()!=colour)left=Math.max(left,e.getValue());
        return left>=KEEP_FOR_BEDS;
    }
    /** How the field is made: from wool of its colour, from white wool dyed with six dyes, or not at all (null). */
    enum Field { WOOL, DYED }
    static Field field(DyeColor base,Map<DyeColor,Integer> wool,Map<DyeColor,Integer> dyes,boolean bedsWaiting){
        if(woolFree(wool,base,bedsWaiting))return Field.WOOL;
        if(base!=DyeColor.WHITE&&dyes.getOrDefault(base,0)>=WOOL_PER_BANNER&&woolFree(wool,DyeColor.WHITE,bedsWaiting))return Field.DYED;
        return null;
    }
    /** Layers the dyes cover, in order: one dye each, as the loom takes them; a layer short of its dye ends the design there. */
    int affordable(Map<DyeColor,Integer> dyes){
        var left=new EnumMap<DyeColor,Integer>(DyeColor.class);left.putAll(dyes);int n=0;
        for(var l:layers){int have=left.getOrDefault(l.colour(),0);if(have<1)break;left.put(l.colour(),have-1);n++;}
        return n;
    }
    /** Dyes the first {@code n} layers take, by colour. */
    Map<DyeColor,Integer> dyesFor(int n){
        var out=new EnumMap<DyeColor,Integer>(DyeColor.class);
        for(int i=0;i<Math.min(n,layers.size());i++)out.merge(layers.get(i).colour(),1,Integer::sum);
        return out;
    }

    // --- sites -----------------------------------------------------------------------------------
    /** Where a banner goes: a standing banner when {@code facing} is null, else a wall banner facing that way. */
    record Site(String key,BlockPos pos,Direction facing){}
    /**
     * The top of a watchtower: a cell on the lookout floor, inside the railing, in the corner away from the
     * guard's post (the north-west inner corner). {@code required} are the plan's own blocks, {@code all} also its decoration.
     */
    static BlockPos towerTop(Set<BlockPos> required,Set<BlockPos> all){
        if(required.isEmpty())return null;
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE,top=Integer.MIN_VALUE;
        for(var p:required){minX=Math.min(minX,p.getX());maxX=Math.max(maxX,p.getX());minZ=Math.min(minZ,p.getZ());maxZ=Math.max(maxZ,p.getZ());top=Math.max(top,p.getY());}
        BlockPos best=null;double far=-1;BlockPos guard=new BlockPos(minX+1,top,minZ+1);
        for(int x=minX+1;x<maxX;x++)for(int z=minZ+1;z<maxZ;z++){
            BlockPos p=new BlockPos(x,top,z);
            if(all.contains(p)||!required.contains(p.below()))continue;
            double d=p.distSqr(guard);if(d>far){far=d;best=p;}
        }
        return best;
    }
    /**
     * A wall banner on the front of a building: on the wall beside the door, a block above the doorway's
     * foot, hanging outward. The front is the side of the door's layer the door lies on.
     */
    static Site front(String key,Set<BlockPos> all,BlockPos door){
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var p:all)if(p.getY()==door.getY()){minX=Math.min(minX,p.getX());maxX=Math.max(maxX,p.getX());minZ=Math.min(minZ,p.getZ());maxZ=Math.max(maxZ,p.getZ());}
        if(minX>maxX)return null;
        Direction out=door.getZ()==minZ?Direction.NORTH:door.getZ()==maxZ?Direction.SOUTH:door.getX()==minX?Direction.WEST:door.getX()==maxX?Direction.EAST:null;
        if(out==null)return null;
        Direction along=out.getClockWise();
        for(int offset:new int[]{2,-2,1,-1,3,-3}){
            BlockPos wall=door.relative(along,offset).above();BlockPos site=wall.relative(out);
            if(all.contains(wall)&&!all.contains(site)&&!all.contains(site.below()))return new Site(key,site,out);
        }
        return null;
    }
    /**
     * A standing banner on top of a fence post beside a gate of the palisade: the highest fence of the
     * column next to the gate, the cell above it free in the plan (a torch post is passed over).
     * {@code gateFacing} is the way through the gate.
     */
    static Site gate(String key,Set<BlockPos> fences,Set<BlockPos> all,BlockPos gate,Direction gateFacing){
        Direction along=gateFacing.getClockWise();
        for(int side:new int[]{1,-1}){
            BlockPos column=gate.relative(along,side),top=null;
            for(int dy=-3;dy<=3;dy++)if(fences.contains(column.above(dy)))top=column.above(dy);
            if(top!=null&&!all.contains(top.above()))return new Site(key,top.above(),null);
        }
        return null;
    }
    /** Columns two blocks from the bell, the four sides first; {@code ground} gives a column's free ground cell, or null. */
    static BlockPos besideBell(BlockPos bell,java.util.function.Function<BlockPos,BlockPos> ground){
        int[][] order={{2,0},{-2,0},{0,2},{0,-2},{2,1},{2,-1},{-2,1},{-2,-1},{1,2},{-1,2},{1,-2},{-1,-2},{2,2},{-2,2},{2,-2},{-2,-2}};
        for(int[] o:order){BlockPos p=ground.apply(bell.offset(o[0],0,o[1]));if(p!=null)return p;}
        return null;
    }
    /** The first site not raised yet, while the village is under its cap. */
    static Site next(List<Site> sites,Set<String> raised){
        if(raised.size()>=MAX_BANNERS)return null;
        for(var s:sites)if(s!=null&&!raised.contains(s.key()))return s;
        return null;
    }
}
