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
    /** Actual settlement footprint. radius is retained only to read older save files. */
    public Bounds bounds;
    public List<TownBuilding> buildings = new ArrayList<>();
    public Set<Long> capturedPieces=new LinkedHashSet<>();
    public Map<UUID, ResidentName> residentNames = new LinkedHashMap<>();
    public Map<UUID, Long> residentPositions = new LinkedHashMap<>();
    public Map<UUID,ResidentRoles.Assignment> residentRoles=new LinkedHashMap<>();
    public long lastExpansion, lastUpgrade;
    public Set<Long> accessBlocks=new LinkedHashSet<>();
    public Set<Long> temporaryWorkbenches=new LinkedHashSet<>();
    public ExpeditionState expedition;
    public List<ExpeditionState.Mine> mines=new ArrayList<>();
    public int completedExpeditions,completedMiningExpeditions,exploredSites,woolScouts;
    public long expeditionDeliveries;
    public int verifiedMiningDeliveries;
    public Map<UUID,Long> mineralDeposits=new LinkedHashMap<>();
    public long nextExpedition;
    public Map<UUID,Long> deposits=new LinkedHashMap<>();
    public Set<Long> worksites=new LinkedHashSet<>();
    public Set<Long> sheepPastures=new LinkedHashSet<>();
    public SheepFarm.State sheepFarm=new SheepFarm.State();
    public VillagePolicy policy=new VillagePolicy();
    public ManagedLand land=new ManagedLand();
    public Map<Long,Integer> roadFailures=new LinkedHashMap<>();
    public Map<String,Long> workshopOutput=new LinkedHashMap<>();
    public Set<ResidentRoles.Role> vacantRoles=new LinkedHashSet<>();
    /** Order and reserve metadata only; the items themselves stay in chests and backpacks. */
    public List<Orders.Order> orders=new ArrayList<>();
    /** Original personal quantities before accepting cargo. Items remain in real inventories. */
    public Map<UUID,Map<Orders.Material,Integer>> cargoKits=new LinkedHashMap<>();
    public Map<Long,List<Long>> treeWork=new LinkedHashMap<>();
    public Set<Long> reforestation=new LinkedHashSet<>();
    static final int MAX_TICKET_CHUNKS=1024;
    static long footprintChunks(Bounds b){return ((long)(b.maxX>>4)-(b.minX>>4)+1)*((long)(b.maxZ>>4)-(b.minZ>>4)+1);}
    static <T> void trimOldest(Set<T> values,int limit){while(values.size()>limit)values.remove(values.iterator().next());}
    /** Historical intent may be forgotten; never discard an inventory or an active worksite. */
    void compactMetadata(){
        if(workshopOutput==null)workshopOutput=new LinkedHashMap<>();
        if(policy==null)policy=new VillagePolicy();if(land==null)land=new ManagedLand();
        if(roadFailures==null)roadFailures=new LinkedHashMap<>();if(vacantRoles==null)vacantRoles=new LinkedHashSet<>();
        if(sheepFarm==null)sheepFarm=new SheepFarm.State();
        trimOldest(plantedCrops,512);trimOldest(reforestation,2048);trimOldest(capturedPieces,4096);
        if(orders==null)orders=new ArrayList<>();
        if(residentRoles==null)residentRoles=new LinkedHashMap<>();
        residentRoles.keySet().retainAll(residents);
        if(cargoKits==null)cargoKits=new LinkedHashMap<>();
        cargoKits.keySet().retainAll(residents);
        residentNames.keySet().retainAll(residents);
        var members=new HashSet<>(residents);if(expedition!=null)members.addAll(expedition.team);
        deposits.keySet().retainAll(members);mineralDeposits.keySet().retainAll(members);
        while(mines.size()>256){var old=mines.stream().filter(m->(m.blocked||m.length>=64)&&(expedition==null||m.entrance!=expedition.target)).findFirst().orElse(null);if(old==null)break;mines.remove(old);}
        for(var it=worksites.iterator();worksites.size()>256&&it.hasNext();)if(!worksiteNeeded(it.next()))it.remove();
    }
    private boolean worksiteNeeded(long packed){
        if(treeWork.containsKey(packed)||fellingTrees.contains(packed))return true;
        if(expedition!=null&&expedition.target==packed||mines.stream().anyMatch(m->m.entrance==packed))return true;
        BlockPos site=BlockPos.of(packed);
        for(var positions:List.of(plantedTrees,fellingTrees,reforestation,treeWork.keySet()))for(long p:positions)
            if(!contains(BlockPos.of(p))&&Math.abs((long)BlockPos.of(p).getX()-site.getX())<=80&&Math.abs((long)BlockPos.of(p).getZ()-site.getZ())<=80)return true;
        return false;
    }
    boolean rememberWorksite(long packed){
        if(worksites.contains(packed))return true;
        if(worksites.size()>=256){var old=worksites.stream().filter(p->!worksiteNeeded(p)).findFirst().orElse(null);if(old==null)return false;worksites.remove(old);}
        worksites.add(packed);return true;
    }
    /** Recover only the old four-block porch overhang; reject unrelated malformed plans. */
    void recoverProjectBounds(){
        if(bounds==null||buildings==null)return;
        var old=bounds;var fixed=new Bounds(old.minX,old.minZ,old.maxX,old.maxZ);
        for(var b:buildings){
            if(b==null||b.blocks==null||!old.contains(BlockPos.of(b.origin)))continue;
            for(var plan:b.blocks)if(plan!=null){
                BlockPos p=plan.position();
                if(p.getX()>=old.minX-4L&&p.getX()<=old.maxX+4L&&p.getZ()>=old.minZ-4L&&p.getZ()<=old.maxZ+4L)fixed.include(p,0);
            }
        }
        if((long)fixed.maxX-fixed.minX<=4096&&(long)fixed.maxZ-fixed.minZ<=4096){bounds=fixed;x=(fixed.minX+fixed.maxX)/2;z=(fixed.minZ+fixed.maxZ)/2;radius=Math.max(24,(int)Math.ceil(Math.hypot(fixed.maxX-fixed.minX,fixed.maxZ-fixed.minZ)/2));}
    }
    boolean recordedWork(BlockPos p){return contains(p)||worksites.stream().map(BlockPos::of).anyMatch(site->Math.abs((long)site.getX()-p.getX())<=80&&Math.abs((long)site.getZ()-p.getZ())<=80);}
    public static final class ResidentName {
        public String json="";
        public boolean visible;
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
    /** Recover the specific old circumscribed-radius mine reference; never alter blocks or items. */
    boolean migrateLegacyMineReference(){
        if(bounds==null||mineEntrance==null||contains(BlockPos.of(mineEntrance))||worksites==null||mines==null
                ||mineDirection<0||mineDirection>3||mineLength<0||mineLength>32)return false;
        BlockPos p=BlockPos.of(mineEntrance);long dx=Math.abs((long)p.getX()-x),dz=Math.abs((long)p.getZ()-z);
        if(p.getY() < -64||p.getY()>=320||Math.abs((long)p.getX())>=29_999_000L||Math.abs((long)p.getZ())>=29_999_000L
                ||radius<24||radius>4096||!(dx==radius-8&&dz==0||dz==radius-8&&dx==0))return false;
        if(mineLength>0){
            var existing=mines.stream().filter(m->m!=null&&m.entrance==mineEntrance).findFirst().orElse(null);
            if(existing!=null&&(existing.direction<0||existing.direction>3||existing.length<0||existing.length>64))return false;
            if(existing==null){if(mines.size()>=256||!worksites.contains(mineEntrance)&&worksites.size()>=256)return false;
                existing=new ExpeditionState.Mine();existing.entrance=mineEntrance;existing.direction=mineDirection;mines.add(existing);}
            existing.length=Math.max(existing.length,mineLength);worksites.add(mineEntrance);
        }
        mineEntrance=null;mineDirection=0;mineLength=0;return true;
    }
    public boolean contains(BlockPos pos) {
        if(bounds!=null)return bounds.contains(pos)&&pos.getY()>=-64&&pos.getY()<320;
        long dx = (long)pos.getX() - x, dz = (long)pos.getZ() - z;
        return dx * dx + dz * dz <= (long)radius * radius && Math.abs(pos.getY() - y) <= 48;
    }
    /** Order references must stay inside the world; a workspace may legitimately sit outside the claim. */
    private static boolean worldPosition(long packed){
        BlockPos p=BlockPos.of(packed);
        return Math.abs((long)p.getX())<29_999_000L&&Math.abs((long)p.getZ())<29_999_000L&&p.getY()>=-64&&p.getY()<320;
    }
    public boolean overlaps(VillageState other) {
        if(bounds!=null&&other.bounds!=null)return bounds.minX<=other.bounds.maxX+8L&&bounds.maxX+8L>=other.bounds.minX
                &&bounds.minZ<=other.bounds.maxZ+8L&&bounds.maxZ+8L>=other.bounds.minZ;
        long dx = (long)x - other.x, dz = (long)z - other.z, r = radius + other.radius + 8L;
        return dx * dx + dz * dz <= r * r;
    }
    public void validate() {
        if(policy==null||land==null||roadFailures==null||roadFailures.size()>128||vacantRoles==null||vacantRoles.contains(null))throw new IllegalArgumentException("Повреждённое планирование хозяйства");
        policy.validate();land.validate(this);
        if(workshopOutput==null||workshopOutput.size()>4||workshopOutput.entrySet().stream().anyMatch(e->!ProductionBuildings.KINDS.contains(e.getKey())||e.getValue()==null||e.getValue()<0))throw new IllegalArgumentException("Повреждённый выпуск мастерских");
        if(policy.privateChests.stream().anyMatch(p->!contains(BlockPos.of(p))))throw new IllegalArgumentException("Личный сундук вне деревни");
        if(roadFailures.entrySet().stream().anyMatch(e->e.getKey()==null||!contains(BlockPos.of(e.getKey()))||e.getValue()==null||e.getValue()<0||e.getValue()>1000000))throw new IllegalArgumentException("Повреждённые дорожные заявки");
        if(sheepFarm==null)throw new IllegalArgumentException("Повреждённое хозяйство");
        sheepFarm.validate(this);
        if(residentRoles==null||residentRoles.size()>1024)throw new IllegalArgumentException("Повреждённые назначения жителей");
        for(var e:residentRoles.entrySet()){
            var a=e.getValue();
            if(e.getKey()==null||!residents.contains(e.getKey())||a==null||a.role==null||a.profession==null||a.profession.length()>64
                    ||a.reason==null||a.reason.length()>160||a.schedule<0||a.schedule>=24)throw new IllegalArgumentException("Повреждённая роль жителя");
        }
        if(woolScouts<0||sheepPastures==null||sheepPastures.size()>8||sheepPastures.stream().anyMatch(p->p==null))throw new IllegalArgumentException("Повреждённые пастбища");
        if(residentPositions==null||residentPositions.size()>1024)throw new IllegalArgumentException("Повреждённые позиции жителей");
        for(var entry:residentPositions.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null)throw new IllegalArgumentException("Повреждённая позиция жителя");
            BlockPos p=BlockPos.of(entry.getValue());
            if(Math.abs((long)p.getX())>=29_999_000L||Math.abs((long)p.getZ())>=29_999_000L||p.getY() < -64||p.getY()>=320)throw new IllegalArgumentException("Позиция жителя вне мира");
        }
        if (name == null || !name.matches("[A-Za-zА-Яа-яЁё0-9_-]{1,32}") || owner == null
                || radius < 24 || radius > (bounds==null?64:4096) || Math.abs((long)x) >= 29_999_000L || Math.abs((long)z) >= 29_999_000L
                || y < -64 || y > 320 || residents == null || residents.size() > 1024 || storage == null || storage.size() > 128
                || furnaces == null || furnaces.size() > 16 || plantedTrees == null || plantedTrees.size() > 512
                || plantedCrops == null || plantedCrops.size() > 512 || completedShelters < 0 || completedShelters > 256
                || mineLength < 0 || mineLength > 32 || mineDirection < 0 || mineDirection > 3 || fellingTrees==null || fellingTrees.size()>128)
            throw new IllegalArgumentException("Повреждённые данные деревни");
        UUID.fromString(owner);
        if(bounds!=null&&(bounds.minX>bounds.maxX||bounds.minZ>bounds.maxZ||(long)bounds.maxX-bounds.minX>4096
                ||(long)bounds.maxZ-bounds.minZ>4096||Math.abs((long)bounds.minX)>=29_999_000L||Math.abs((long)bounds.maxX)>=29_999_000L
                ||Math.abs((long)bounds.minZ)>=29_999_000L||Math.abs((long)bounds.maxZ)>=29_999_000L))throw new IllegalArgumentException("Повреждённые границы поселения");
        if(buildings==null||buildings.size()>256||capturedPieces==null||capturedPieces.size()>4096||residentNames==null||accessBlocks==null||accessBlocks.size()>2048
                ||temporaryWorkbenches==null||temporaryWorkbenches.size()>256)throw new IllegalArgumentException("Повреждённый план поселения");
        for(TownBuilding building:buildings)building.validate(this);
        if(expedition!=null)expedition.validate();
        if(mines==null||mines.size()>256||deposits==null||mineralDeposits==null||verifiedMiningDeliveries<0||completedExpeditions<0||completedMiningExpeditions<0||expeditionDeliveries<0||exploredSites<0)throw new IllegalArgumentException("Повреждённые рабочие участки");
        for(var mine:mines)if(mine==null||mine.direction<0||mine.direction>3||mine.length<0||mine.length>64
                ||mine.drySegments<0||mine.drySegments>64||mine.hazard==null||mine.hazard.length()>160||mine.iron==null||mine.coal==null||mine.iron.size()>128||mine.coal.size()>128
                ||mine.iron.contains(null)||mine.coal.contains(null)||mine.lastSurvey<0)throw new IllegalArgumentException("Повреждённая шахта");
        for(var counters:List.of(deposits,mineralDeposits))if(counters.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null||e.getValue()<0))throw new IllegalArgumentException("Повреждённый учёт поставок");
        for (Long pos : storage) if (pos == null || !contains(BlockPos.of(pos))) throw new IllegalArgumentException("Склад вне деревни");
        for (Long pos : furnaces) if (pos == null || !contains(BlockPos.of(pos))) throw new IllegalArgumentException("Печь вне деревни");
        if(worksites==null||worksites.size()>256||treeWork==null||treeWork.size()>256||reforestation==null||reforestation.size()>2048)throw new IllegalArgumentException("Повреждённые рабочие участки");
        for(var tree:treeWork.values())if(tree==null||tree.size()>256||tree.stream().anyMatch(p->p==null||!recordedWork(BlockPos.of(p))))throw new IllegalArgumentException("Повреждённый план лесозаготовки");
        for(Set<Long> positions:List.of(plantedTrees,plantedCrops,fellingTrees))for(Long pos:positions)
            if(pos==null||!recordedWork(BlockPos.of(pos)))throw new IllegalArgumentException("Рабочая область вне деревни");
        if(cargoKits==null||cargoKits.size()>1024)throw new IllegalArgumentException("Invalid cargo kits");
        for(var e:cargoKits.entrySet())if(e.getKey()==null||!residents.contains(e.getKey())||e.getValue()==null
                ||e.getValue().size()>Orders.Material.values().length||e.getValue().entrySet().stream().anyMatch(k->k.getKey()==null||k.getValue()==null||k.getValue()<0||k.getValue()>65536))
            throw new IllegalArgumentException("Invalid cargo kit");
        if(orders==null||orders.size()>Orders.MAX_ORDERS)throw new IllegalArgumentException("Повреждённые заказы");
        for(Orders.Order order:orders){
            if(order==null||order.kind()==null||order.purpose==null||order.purpose.isEmpty()||order.purpose.length()>Orders.MAX_PURPOSE_LENGTH
                    ||order.localOnly&&(order.kind()!=Orders.Material.PLANKS||order.returnCargo())
                    ||order.quantity<0||order.returnCargo()&&order.quantity<1||order.quantity>65536||order.delivered<0||order.delivered>order.quantity
                    ||order.carrier==null||order.inTransit<0||order.inTransit>65536||order.observedStock<0
                    ||order.recipient==null||!worldPosition(order.delivery)||!worldPosition(order.work))throw new IllegalArgumentException("Повреждённый заказ");
            if(!order.recipient.isEmpty())UUID.fromString(order.recipient);
            if(!order.carrier.isEmpty())UUID.fromString(order.carrier);
        }
        if (shelter != null && !contains(BlockPos.of(shelter))) throw new IllegalArgumentException("Стройка вне деревни");
        if (mineEntrance != null && !contains(BlockPos.of(mineEntrance))) throw new IllegalArgumentException("Шахта вне деревни");
    }
}
