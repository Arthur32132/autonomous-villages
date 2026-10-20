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
    public Map<Long,List<Long>> treeWork=new LinkedHashMap<>();
    public Set<Long> reforestation=new LinkedHashSet<>();
    public Long mineEntrance;
    public int mineDirection, mineLength;
    /** Staircase mine: digging, finished (its depth, the claim edge or the world bottom) or blocked (an obstacle the excavator refuses); failed runs at the current face. */
    public String mineStatus=MINE_DIGGING;
    public int mineFailures;
    /** Game time a blocked shaft may be tried again when no other edge fits. */
    public long mineRetryAt;
    static final String MINE_DIGGING="digging",MINE_FINISHED="finished",MINE_BLOCKED="blocked";
    public int completedShelters;
    /** Actual settlement footprint. radius is kept for the chunk budget and POI queries. */
    public Bounds bounds;
    public List<TownBuilding> buildings = new ArrayList<>();
    public Set<Long> capturedPieces=new LinkedHashSet<>();
    public Map<UUID, ResidentName> residentNames = new LinkedHashMap<>();
    public Map<UUID, Long> residentPositions = new LinkedHashMap<>();
    public Map<UUID,ResidentRoles.Assignment> residentRoles=new LinkedHashMap<>();
    public VillagePolicy policy=new VillagePolicy();
    /** Torches the villagers placed themselves; a broken one is simply forgotten. */
    public Set<Long> lights=new LinkedHashSet<>();
    public Chronicle.Data chronicle=new Chronicle.Data();
    public long lastBirth;
    /** The last game day each resident brought home its profession's product. */
    public Map<UUID,Long> productDays=new LinkedHashMap<>();
    /** Game time the residents last called an iron golem to their defence. */
    public long lastGolem;
    /** The morning the news was last told (overworld day), with the totals and population of that morning. */
    public long newsDay=-1;
    public Map<String,Long> newsTotals;
    public int newsResidents=-1;
    /** Residents lost and still without a sign at the cemetery; the cemetery's corner once chosen. */
    public List<Grave> graves=new ArrayList<>();
    public Long cemetery;
    /** Signs beside home doors (position) and the building each names (origin). */
    public Map<Long,Long> nameplates=new LinkedHashMap<>();
    /** Items each player has handed to the store, their names as last seen, and who was made an honorary resident. */
    public Map<UUID,Long> donations=new LinkedHashMap<>();
    public Map<UUID,String> donorNames=new LinkedHashMap<>();
    public Set<UUID> honoured=new LinkedHashSet<>();
    /** The game day each landmark last gave its goods (forge, market, mill, tavern, dock). */
    public Map<String,Long> goodsDays=new LinkedHashMap<>();
    /** The game day of the last fair. */
    public long fairDay=-1;
    /** The open request to players, if any, and the game time before which no new one is posted. */
    public Request request;
    public long nextRequest;
    /** The notice board: the sign the village itself put up by the bell; no other sign is ever written over. */
    public Long noticeBoard;
    /** Saved state of each optional feature (see {@link Features}), JSON by feature key. */
    public Map<String,String> features=new LinkedHashMap<>();
    public static final class Request {
        public String key="";
        public int amount,delivered;
        public long posted;
    }
    static final int MAX_TICKET_CHUNKS=1024;
    static long footprintChunks(Bounds b){return ((long)(b.maxX>>4)-(b.minX>>4)+1)*((long)(b.maxZ>>4)-(b.minZ>>4)+1);}
    static <T> void trimOldest(Set<T> values,int limit){while(values.size()>limit)values.remove(values.iterator().next());}
    /** Forgets old intent and fills in missing collections; never touches an inventory or the world. */
    void compactMetadata(){
        if(residents==null)residents=new LinkedHashSet<>();
        if(storage==null)storage=new LinkedHashSet<>();
        if(furnaces==null)furnaces=new LinkedHashSet<>();
        if(plantedTrees==null)plantedTrees=new LinkedHashSet<>();
        if(plantedCrops==null)plantedCrops=new LinkedHashSet<>();
        if(fellingTrees==null)fellingTrees=new LinkedHashSet<>();
        if(treeWork==null)treeWork=new LinkedHashMap<>();
        if(reforestation==null)reforestation=new LinkedHashSet<>();
        if(buildings==null)buildings=new ArrayList<>();
        if(capturedPieces==null)capturedPieces=new LinkedHashSet<>();
        if(residentNames==null)residentNames=new LinkedHashMap<>();
        if(residentPositions==null)residentPositions=new LinkedHashMap<>();
        if(residentRoles==null)residentRoles=new LinkedHashMap<>();
        if(policy==null)policy=new VillagePolicy();
        if(lights==null)lights=new LinkedHashSet<>();
        if(features==null)features=new LinkedHashMap<>();
        features.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null||e.getValue().length()>Features.MAX_STATE_CHARS);
        if(chronicle==null)chronicle=new Chronicle.Data();
        if(productDays==null)productDays=new LinkedHashMap<>();
        if(newsTotals!=null)newsTotals.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null);
        if(graves==null)graves=new ArrayList<>();
        if(nameplates==null)nameplates=new LinkedHashMap<>();
        if(donations==null)donations=new LinkedHashMap<>();
        if(donorNames==null)donorNames=new LinkedHashMap<>();
        if(honoured==null)honoured=new LinkedHashSet<>();
        if(goodsDays==null)goodsDays=new LinkedHashMap<>();
        if(request!=null&&(request.key==null||request.key.isEmpty()||request.key.length()>64||request.amount<1||request.amount>VillageRequests.MAX_AMOUNT||request.delivered<0))request=null;
        goodsDays.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null||!Landmarks.kind(e.getKey()));
        donations.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null||e.getValue()<0);
        while(donations.size()>256)donations.remove(donations.keySet().iterator().next());
        donorNames.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null||e.getValue().length()>32||!donations.containsKey(e.getKey()));
        honoured.removeIf(Objects::isNull);trimOldest(honoured,256);
        nameplates.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null||!contains(BlockPos.of(e.getKey())));
        while(nameplates.size()>256)nameplates.remove(nameplates.keySet().iterator().next());
        graves.removeIf(g->g==null||g.name==null);
        while(graves.size()>16)graves.removeFirst();
        if(cemetery!=null&&!contains(BlockPos.of(cemetery)))cemetery=null;
        if(noticeBoard!=null&&!contains(BlockPos.of(noticeBoard)))noticeBoard=null;
        productDays.keySet().retainAll(residents);
        chronicle.sanitize();
        trimOldest(plantedCrops,512);trimOldest(reforestation,2048);trimOldest(capturedPieces,4096);trimOldest(lights,1024);trimOldest(plantedTrees,512);
        while(treeWork.size()>256)treeWork.remove(treeWork.keySet().iterator().next());
        residentRoles.keySet().retainAll(residents);
        residentNames.keySet().retainAll(residents);
        residentPositions.keySet().retainAll(residents);
        storage.removeIf(p->p==null||!contains(BlockPos.of(p)));furnaces.removeIf(p->p==null||!contains(BlockPos.of(p)));
        for(Set<Long> positions:List.of(plantedCrops,lights))positions.removeIf(p->p==null||!contains(BlockPos.of(p)));
        for(Set<Long> positions:List.of(plantedTrees,fellingTrees,reforestation))positions.removeIf(p->p==null||!nearby(BlockPos.of(p)));
        treeWork.entrySet().removeIf(e->e.getKey()==null||e.getValue()==null||!nearby(BlockPos.of(e.getKey())));
        for(var tree:treeWork.values())tree.removeIf(p->p==null||!nearby(BlockPos.of(p)));
        buildings.removeIf(b->b==null||!b.valid(this));
        if(mineEntrance!=null&&!contains(BlockPos.of(mineEntrance)))mineEntrance=null;
        mineDirection=Math.floorMod(mineDirection,4);mineLength=Math.clamp(mineLength,0,64);
        if(mineEntrance==null||mineStatus==null||!Set.of(MINE_DIGGING,MINE_FINISHED,MINE_BLOCKED).contains(mineStatus))mineStatus=MINE_DIGGING;
        mineFailures=Math.clamp(mineFailures,0,16);
    }
    /** Woods and fields just outside the footprint where residents may still fell trees and walk. */
    static final int OUTSKIRTS=48;
    boolean nearby(BlockPos p){
        if(p.getY()<-64||p.getY()>=320)return false;
        if(bounds==null){long dx=(long)p.getX()-x,dz=(long)p.getZ()-z,r=radius+OUTSKIRTS;return dx*dx+dz*dz<=r*r;}
        return p.getX()>=bounds.minX-OUTSKIRTS&&p.getX()<=bounds.maxX+OUTSKIRTS&&p.getZ()>=bounds.minZ-OUTSKIRTS&&p.getZ()<=bounds.maxZ+OUTSKIRTS;
    }
    boolean recordedWork(BlockPos p){return nearby(p);}
    /** Chests and furnaces become village metadata only inside the footprint; saplings also in the outskirts. */
    boolean mayRecordPlacement(BlockPos p,boolean sapling){return sapling?nearby(p):contains(p);}
    public static final class Grave {
        public String name="",role="";
        public long day;
        public Grave(){}
        public Grave(String name,String role,long day){this.name=name;this.role=role;this.day=day;}
    }
    public static final class ResidentName {
        public String json="";
        public boolean visible;
        /** The mod never replaced this name (baby, NoAI or never loaded): release must leave it as is. */
        public boolean untouched;
        static ResidentName untouched(){var name=new ResidentName();name.untouched=true;return name;}
    }
    public static final class Bounds {
        public int minX,minZ,maxX,maxZ;
        public Bounds() { }
        public Bounds(int minX,int minZ,int maxX,int maxZ){this.minX=minX;this.minZ=minZ;this.maxX=maxX;this.maxZ=maxZ;}
        public boolean contains(BlockPos p){return p.getX()>=minX&&p.getX()<=maxX&&p.getZ()>=minZ&&p.getZ()<=maxZ;}
        public void include(BlockPos p,int margin){minX=Math.min(minX,p.getX()-margin);maxX=Math.max(maxX,p.getX()+margin);minZ=Math.min(minZ,p.getZ()-margin);maxZ=Math.max(maxZ,p.getZ()+margin);}
    }
    public VillageState() { }
    public VillageState(String name, UUID owner, BlockPos center, int radius) {
        this.name = name; this.owner = owner.toString(); this.x = center.getX(); this.y = center.getY();
        this.z = center.getZ(); this.radius = radius;
    }
    public BlockPos center() { return new BlockPos(x, y, z); }
    BlockPos localMineColumn(net.minecraft.core.Direction direction){
        if(bounds==null)return center().relative(direction,radius-8);
        int insetX=Math.min(8,(bounds.maxX-bounds.minX)/2),insetZ=Math.min(8,(bounds.maxZ-bounds.minZ)/2);
        int columnX=direction.getStepX()==0?Math.clamp(x,bounds.minX,bounds.maxX):direction.getStepX()>0?bounds.maxX-insetX:bounds.minX+insetX;
        int columnZ=direction.getStepZ()==0?Math.clamp(z,bounds.minZ,bounds.maxZ):direction.getStepZ()>0?bounds.maxZ-insetZ:bounds.minZ+insetZ;
        return new BlockPos(columnX,y,columnZ);
    }
    public boolean contains(BlockPos pos) {
        if(bounds!=null)return bounds.contains(pos)&&pos.getY()>=-64&&pos.getY()<320;
        long dx = (long)pos.getX() - x, dz = (long)pos.getZ() - z;
        return dx * dx + dz * dz <= (long)radius * radius && Math.abs(pos.getY() - y) <= 48;
    }
    public boolean overlaps(VillageState other) {
        if(bounds!=null&&other.bounds!=null)return bounds.minX<=other.bounds.maxX+8L&&bounds.maxX+8L>=other.bounds.minX
                &&bounds.minZ<=other.bounds.maxZ+8L&&bounds.maxZ+8L>=other.bounds.minZ;
        long dx = (long)x - other.x, dz = (long)z - other.z, r = radius + other.radius + 8L;
        return dx * dx + dz * dz <= r * r;
    }
    /**
     * Only checks what would break the game if wrong: identity, footprint and collection sizes.
     * Stale positions are dropped by {@link #compactMetadata()} instead of rejecting the whole save.
     */
    public void validate() {
        if (name == null || !name.matches("[A-Za-zА-Яа-яЁё0-9_-]{1,32}") || owner == null
                || radius < 24 || radius > 4096 || Math.abs((long)x) >= 29_999_000L || Math.abs((long)z) >= 29_999_000L
                || y < -64 || y > 320 || residents == null || residents.size() > 1024 || residents.contains(null)
                || storage == null || storage.size() > 128 || furnaces == null || furnaces.size() > 16
                || completedShelters < 0 || completedShelters > 256 || mineLength < 0 || mineLength > 64 || mineDirection < 0 || mineDirection > 3
                || lastBirth < 0 || lastGolem < 0)
            throw new IllegalArgumentException("Повреждённые данные деревни");
        UUID.fromString(owner);
        if(bounds!=null&&(bounds.minX>bounds.maxX||bounds.minZ>bounds.maxZ||(long)bounds.maxX-bounds.minX>4096
                ||(long)bounds.maxZ-bounds.minZ>4096||Math.abs((long)bounds.minX)>=29_999_000L||Math.abs((long)bounds.maxX)>=29_999_000L
                ||Math.abs((long)bounds.minZ)>=29_999_000L||Math.abs((long)bounds.maxZ)>=29_999_000L))throw new IllegalArgumentException("Повреждённые границы поселения");
        if(buildings==null||buildings.size()>256||capturedPieces==null||capturedPieces.size()>4096||residentNames==null||residentPositions==null||residentPositions.size()>1024
                ||residentRoles==null||residentRoles.size()>1024||policy==null||lights==null||lights.size()>1024||chronicle==null)
            throw new IllegalArgumentException("Повреждённый план поселения");
        policy.validate();
        for(var a:residentRoles.values())if(a==null||a.role==null||a.schedule<0||a.schedule>=24)throw new IllegalArgumentException("Повреждённая роль жителя");
    }
}
