package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Скотный двор: farmers breed cows with wheat and chickens with seeds taken from the store, walk the yard once a day for
 * the eggs, and when a herd outgrows the yard while the store is short of meals, a resident with a sword or an axe culls
 * one adult of the surplus kind; its drops go to the store and the raw meat through the furnace (SmeltJob). Named,
 * leashed and other villages' animals are never touched, and no kind is culled below two adults.
 * Switch {@code livestock} in {@link ModConfig}; state in {@link VillageState#features}.
 */
final class LivestockFeature implements Features.Feature {
    static final String KEY="livestock";
    /** Animals this far outside the village bounds still belong to its yard. */
    static final int YARD=16;
    /** A kind is never culled below this many adults. */
    static final int MIN_KEEP=2;
    /** Culling only while the store holds fewer meals than this. */
    static final int MEALS_SHORT=6;
    /** Plenty of bread: wheat beyond the two for a pair may go to the cows. */
    static final int BREAD_PLENTY=16;
    static final long BREED_COOLDOWN=2400,CENSUS_EVERY=200,BIRTH_WATCH=1200;
    /** Livestock work happens by day only: from the morning bell until before dusk. */
    static final int WORK_FROM=1000,WORK_UNTIL=11000;
    static final Predicate<ItemStack> RAW_MEAT=s->s.is(Items.BEEF)||s.is(Items.CHICKEN);
    static final Predicate<ItemStack> SMELT_FUEL=s->VillageCrafting.FUEL.test(s)||s.is(ItemTags.PLANKS);
    static final Predicate<ItemStack> EGG=s->s.is(Items.EGG)||s.is(Items.BLUE_EGG)||s.is(Items.BROWN_EGG);

    enum Kind {
        COW(8,"коров","корову","телёнок","пшеница",s->s.is(ItemTags.COW_FOOD)),
        CHICKEN(10,"кур","курицу","цыплёнок","семена",s->s.is(ItemTags.CHICKEN_FOOD));
        /** The herd within the yard stops being bred at this size, and only animals beyond it are culled. */
        final int cap;
        final String plural,one,baby,feedLabel;
        /** One shared instance per kind: VillageStock caches its counts per predicate. */
        final Predicate<ItemStack> feed;
        Kind(int cap,String plural,String one,String baby,String feedLabel,Predicate<ItemStack> feed){this.cap=cap;this.plural=plural;this.one=one;this.baby=baby;this.feedLabel=feedLabel;this.feed=feed;}
        boolean is(Animal a){return this==COW?a instanceof Cow:a instanceof Chicken;}
    }

    /** Saved per village. */
    static final class State {
        Map<String,Long> readyAt=new HashMap<>();
        long cullDay=Long.MIN_VALUE,eggDay=Long.MIN_VALUE,lineDay=Long.MIN_VALUE;
        /** The pair fed last: its kind, when and where, watched for the newborn. */
        String pendingKind;long pendingAt;long pendingPos;
    }
    /** One kind within the yard: all animals, adults, adults free to breed and adults that may be culled. */
    record Herd(int herd,int adults,int breedable,int cullable){}
    /** The last census of one village and the work it found; each piece of work is handed out once. */
    private static final class Yard {
        final State state;
        final EnumMap<Kind,Herd> herds=new EnumMap<>(Kind.class);
        final EnumSet<Kind> breed=EnumSet.noneOf(Kind.class);
        Kind cull;
        boolean eggRound,smelt;
        int bread,meat;
        BlockPos furnace;
        Yard(State state){this.state=state;}
    }
    private final Map<Village,Yard> yards=new WeakHashMap<>();

    @Override public String key(){return KEY;}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.livestock;}

    // --- rules, free of the world -------------------------------------------------------------
    /** Breeding: the herd is below its cap, a free pair stands in the yard, the kind's cooldown is over, and the feed beyond the reserve covers a pair. */
    static boolean mayBreed(int herd,int breedable,int cap,boolean ready,int feed,int reserve){return wantsYoung(herd,breedable,cap,ready)&&spareFeed(feed,reserve);}
    static boolean wantsYoung(int herd,int breedable,int cap,boolean ready){return herd<cap&&breedable>=2&&ready;}
    static boolean spareFeed(int feed,int reserve){return feed>=2+reserve;}
    /** Culling: only a surplus beyond the cap, never below {@link #MIN_KEEP} adults, only while meals are short, once a game day. */
    static boolean mayCull(int herd,int adults,int cullable,int cap,int meals,long day,long cullDay){return surplus(herd,adults,cullable,cap)&&meals<MEALS_SHORT&&day!=cullDay;}
    static boolean surplus(int herd,int adults,int cullable,int cap){return herd>cap&&adults>MIN_KEEP&&cullable>0;}
    /** The egg round: once a game day, by day, and only when there are eggs on the ground. */
    static boolean eggRoundDue(long day,long eggDay,long hour,int eggs){return day!=eggDay&&daytime(hour)&&eggs>0;}
    static boolean daytime(long hour){return hour>=WORK_FROM&&hour<WORK_UNTIL;}
    /** At most one chronicle line a game day. */
    static boolean lineDue(long day,long lineDay){return day!=lineDay;}
    /** A cooldown that ends at {@code readyAt}; a clock that went backwards (another world, /time) never locks it. */
    static boolean ready(long now,long readyAt,long cooldown){return now>=readyAt||readyAt-now>cooldown;}
    /** A baby of this age was born at or after {@code since}: vanilla babies start at -24000 and age one a tick. */
    static boolean bornSince(int age,long now,long since){return age<0&&now>=since&&age<=-24000+(now-since);}
    /** Wheat is bread first: cows get it only beyond six for the baker, unless bread is plentiful. Seeds keep four for the fields. */
    static int feedReserve(Kind kind,int bread){return kind==Kind.COW?bread>=BREAD_PLENTY?0:6:4;}
    /** Within the village bounds widened by {@link #YARD}, or the radius when no bounds are surveyed yet. */
    static boolean inYard(VillageState s,BlockPos p){
        if(p.getY()<-64||p.getY()>=320)return false;
        if(s.bounds==null){long dx=(long)p.getX()-s.x,dz=(long)p.getZ()-s.z,r=s.radius+YARD;return dx*dx+dz*dz<=r*r;}
        return p.getX()>=s.bounds.minX-YARD&&p.getX()<=s.bounds.maxX+YARD&&p.getZ()>=s.bounds.minZ-YARD&&p.getZ()<=s.bounds.maxZ+YARD;
    }

    // --- the world --------------------------------------------------------------------------------
    static AABB yardBox(VillageState s){
        int minX,minZ,maxX,maxZ;
        if(s.bounds==null){minX=s.x-s.radius;maxX=s.x+s.radius;minZ=s.z-s.radius;maxZ=s.z+s.radius;}
        else{minX=s.bounds.minX;maxX=s.bounds.maxX;minZ=s.bounds.minZ;maxZ=s.bounds.maxZ;}
        return new AABB(minX-YARD,Math.max(-64,s.y-64),minZ-YARD,maxX+YARD+1,Math.min(320,s.y+64),maxZ+YARD+1);
    }
    /** In this village's yard and not inside another village. */
    static boolean ours(Village v,BlockPos p){return inYard(v.state,p)&&v.runtime.villages.values().stream().noneMatch(o->o!=v&&o.state.contains(p));}
    /** An animal residents may feed or cull: alive, nobody's pet (no name, no lead), in this village's yard. */
    static boolean touchable(Village v,Animal a){return a.isAlive()&&!a.hasCustomName()&&!a.isLeashed()&&ours(v,a.blockPosition());}
    static List<Animal> animals(Village v,Kind kind,AABB box,Predicate<Animal> test){return v.level.getEntitiesOfClass(Animal.class,box,a->kind.is(a)&&test.test(a));}
    static Herd census(Village v,Kind kind){
        int herd=0,adults=0,breedable=0,cullable=0;
        for(Animal a:animals(v,kind,yardBox(v.state),a->a.isAlive()&&ours(v,a.blockPosition()))){
            herd++;
            if(a.isBaby())continue;
            adults++;
            if(a.hasCustomName()||a.isLeashed())continue;
            if(a.canFallInLove()&&a.getAge()==0)breedable++;
            if(!a.isInLove())cullable++;
        }
        return new Herd(herd,adults,breedable,cullable);
    }
    static List<ItemEntity> eggs(Village v,AABB box){return v.level.getEntitiesOfClass(ItemEntity.class,box,e->e.isAlive()&&EGG.test(e.getItem())&&ours(v,e.blockPosition()));}
    /** A loaded furnace of the village whose input slot is free or already holds meat. */
    static BlockPos meatFurnace(Village v){
        for(long packed:v.state.furnaces){
            BlockPos p=BlockPos.of(packed);
            if(v.level.hasChunkAt(p)&&v.level.getBlockEntity(p) instanceof FurnaceBlockEntity f&&!f.isLocked()&&(f.getItem(0).isEmpty()||RAW_MEAT.test(f.getItem(0))))return p;
        }
        return null;
    }

    private Yard yard(Village v){return yards.computeIfAbsent(v,x->new Yard(Features.load(x,KEY,State.class,State::new)));}
    private static void save(Village v,Yard y){Features.store(v,KEY,y.state);}

    @Override public void tick(Village v,long time){
        if(time%CENSUS_EVERY!=53)return;
        var y=yard(v);var s=y.state;
        long clock=v.level.getOverworldClockTime(),day=Math.floorDiv(clock,24000),hour=Math.floorMod(clock,24000);
        boolean work=daytime(hour)&&!v.alarm();
        y.cull=null;y.breed.clear();y.eggRound=false;y.smelt=false;y.furnace=null;
        // By night nobody tends the yard: no census, unless a pair fed at dusk is still watched for its young.
        if(!work&&s.pendingKind==null)return;
        for(Kind k:Kind.values())y.herds.put(k,census(v,k));
        watchBirth(v,y,time,day);
        if(!work)return;
        y.bread=v.stock.count(Food.BREAD);
        int meals=Food.meals(v.stock);
        for(Kind k:Kind.values()){var h=y.herds.get(k);if(mayCull(h.herd(),h.adults(),h.cullable(),k.cap,meals,day,s.cullDay)){y.cull=k;break;}}
        // Feed is checked against the store and the farmer's own bag when the work is handed out.
        for(Kind k:Kind.values()){var h=y.herds.get(k);if(wantsYoung(h.herd(),h.breedable(),k.cap,ready(time,s.readyAt.getOrDefault(k.name(),0L),BREED_COOLDOWN)))y.breed.add(k);}
        y.eggRound=eggRoundDue(day,s.eggDay,hour,y.herds.get(Kind.CHICKEN).herd()>0?eggs(v,yardBox(v.state)).size():0);
        y.meat=v.stock.count(RAW_MEAT);
        y.furnace=y.meat>0&&v.stock.count(SMELT_FUEL)>0?meatFurnace(v):null;
        y.smelt=y.furnace!=null;
    }
    /** The pair fed last brought a calf or a chick: counted, and told in the chronicle at most once a day. */
    private void watchBirth(Village v,Yard y,long time,long day){
        var s=y.state;
        if(s.pendingKind==null)return;
        Kind kind;try{kind=Kind.valueOf(s.pendingKind);}catch(IllegalArgumentException e){s.pendingKind=null;save(v,y);return;}
        if(time<s.pendingAt||time-s.pendingAt>BIRTH_WATCH){s.pendingKind=null;save(v,y);return;}
        BlockPos at=BlockPos.of(s.pendingPos);
        if(!v.level.hasChunkAt(at))return;
        if(animals(v,kind,new AABB(at).inflate(12),a->a.isAlive()&&bornSince(a.getAge(),time,s.pendingAt)).isEmpty())return;
        s.pendingKind=null;
        v.chronicle.add("livestock:born:"+kind.name().toLowerCase(Locale.ROOT),1);
        if(lineDue(day,s.lineDay)){s.lineDay=day;v.chronicle.announce("На скотном дворе прибавление: "+kind.baby);}
        save(v,y);
    }
    /** Called by the job once both animals of a pair are fed. */
    void bred(Village v,Kind kind,BlockPos at,long time){
        var y=yard(v);y.state.pendingKind=kind.name();y.state.pendingAt=time;y.state.pendingPos=at.asLong();save(v,y);
    }

    @Override public Job job(Village v,Worker w,ResidentRoles.Role role){
        var y=yards.get(v);
        if(y==null||y.cull==null&&y.breed.isEmpty()&&!y.eggRound&&!y.smelt)return null;
        boolean farmer=role==ResidentRoles.Role.FARMER||role==null&&v.smallCrew();
        if(y.smelt&&(farmer||role==ResidentRoles.Role.STEWARD)&&v.activeJobs("smelt")==0&&w.ready("smelt")){
            y.smelt=false;
            if(v.level.hasChunkAt(y.furnace)&&v.level.getBlockEntity(y.furnace) instanceof FurnaceBlockEntity)
                return new SmeltJob(w,y.furnace,RAW_MEAT,Math.min(16,y.meat),"мясо");
        }
        if(v.activeJobs(LivestockJob.KIND)>0||!w.ready(LivestockJob.KIND))return null;
        long now=v.level.getGameTime(),day=Math.floorDiv(v.level.getOverworldClockTime(),24000);
        var s=y.state;
        if(y.cull!=null&&LivestockJob.weapon(w)>=0){
            Kind k=y.cull;y.cull=null;s.cullDay=day;save(v,y);
            return new LivestockJob(w,this,LivestockJob.Mode.CULL,k);
        }
        if(!farmer)return null;
        if(y.eggRound){y.eggRound=false;s.eggDay=day;save(v,y);return new LivestockJob(w,this,LivestockJob.Mode.EGGS,Kind.CHICKEN);}
        for(Kind k:y.breed){
            if(!spareFeed(v.stock.count(k.feed)+w.count(k.feed),feedReserve(k,y.bread)))continue;
            y.breed.remove(k);s.readyAt.put(k.name(),now+BREED_COOLDOWN);save(v,y);
            return new LivestockJob(w,this,LivestockJob.Mode.BREED,k);
        }
        return null;
    }
}
