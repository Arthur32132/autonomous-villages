package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;

/**
 * Metadata only: every item stays in a real chest, backpack or dropped entity. The ledger
 * separates free stock, a personal emergency kit, material reserved for an order and cargo
 * already in transit, so a plan never counts another worker's load as free village stock.
 */
final class Orders {
    /** A tag-level material, so any suitable wood or wool colour satisfies the same order. */
    enum Material {
        PLANKS(s -> s.is(ItemTags.PLANKS)),
        LOGS(s -> s.is(ItemTags.LOGS_THAT_BURN)),
        WOOL(s -> s.is(ItemTags.WOOL)),
        BEDS(s -> s.is(ItemTags.BEDS)),
        SHEARS(s -> s.is(Items.SHEARS)&&Supplies.usable(s)),
        IRON(s -> s.is(Items.IRON_INGOT)),
        COBBLESTONE(s -> s.is(Items.COBBLESTONE)),
        WOODEN_DOOR(s -> s.is(ItemTags.WOODEN_DOORS)),
        WOODEN_STAIRS(s -> s.is(ItemTags.WOODEN_STAIRS)),
        RAW_IRON(s -> s.is(Items.RAW_IRON)),
        FUEL(s -> s.is(Items.COAL)||s.is(Items.CHARCOAL)),
        STICKS(s -> s.is(Items.STICK)),
        FOOD(Supplies::food),
        SEEDS(s -> s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS)),
        IRON_PICKAXE(s -> s.is(Items.IRON_PICKAXE)&&Supplies.usable(s)),
        BUCKET(s -> s.is(Items.BUCKET)||s.is(Items.WATER_BUCKET)),
        SHIELD(s -> s.is(Items.SHIELD)&&Supplies.usable(s)),
        CHESTPLATE(s -> s.is(Items.IRON_CHESTPLATE)&&Supplies.usable(s)),
        HELMET(s -> s.is(Items.IRON_HELMET)&&Supplies.usable(s)),
        LEGGINGS(s -> s.is(Items.IRON_LEGGINGS)&&Supplies.usable(s)),
        BOOTS(s -> s.is(Items.IRON_BOOTS)&&Supplies.usable(s)),
        SURVEY_PICK(s -> s.is(Items.STONE_PICKAXE)&&s.isCorrectToolForDrops(net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState())
                &&s.getMaxDamage()-s.getDamageValue()>=32);
        final Predicate<ItemStack> accepts;
        Material(Predicate<ItemStack> accepts){this.accepts=accepts;}
        static Material named(String name){for(Material m:values())if(m.name().equals(name))return m;return null;}
    }

    /** Production quantity is a current stock target; delivered is its observed coverage.
     * Only return_cargo keeps a historical delivery counter. Never an item itself. */
    static final class Order {
        public String material="";
        public String purpose="";
        public int quantity;
        public int delivered;
        public String recipient="";
        public long delivery;
        public long work;
        public boolean transit;
        public String carrier="";
        public int inTransit;
        public int observedStock;
        /** A manufacturing output target; only its selected inputs may be fetched externally. */
        public boolean localOnly;
        public long retryAfter;
        public String waiting="";
        public long created;
        transient int craftedCoverage;
        Material kind(){return Material.named(material);}
        int outstanding(){return Math.max(0,quantity-delivered);}
        boolean open(){return outstanding()>0;}
        boolean returnCargo(){return "return_cargo".equals(purpose);}
    }

    /** One derived need: a material class, the reason and the required quantity. */
    record Demand(Material material,String purpose,int quantity){ }

    record Allocation(int chest,Map<UUID,Integer> bags){
        int total(){return chest+bags.values().stream().mapToInt(Integer::intValue).sum();}
    }

    /** The same physical pools serve dispatch and self-fetch; every unit is allocated once. */
    static Map<Order,Allocation> allocations(Village v){return allocations(v,List.of(Material.values()));}
    static Map<Order,Allocation> allocations(Village v,Material material){return allocations(v,List.of(material));}
    private static Map<Order,Allocation> allocations(Village v,List<Material> materials){
        refreshStock(v);
        var out=new IdentityHashMap<Order,Allocation>();
        var active=v.workers.values().stream().filter(r->r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE
                &&r.body().isAlive()).sorted(Comparator.comparing(r->r.id.toString())).toList();
        for(var material:materials){
            int chest=v.chestCount(material);
            var bags=new LinkedHashMap<UUID,Integer>();
            for(var r:active){
                int assigned=v.state.orders.stream().filter(o->!o.returnCargo()&&o.kind()==material&&o.recipient.equals(r.id.toString()))
                        .mapToInt(o->Math.max(0,o.delivered-o.craftedCoverage)).sum();
                bags.put(r.id,Math.max(0,r.count(material)-Math.max(r.kit(material),assigned)));
            }
            // Honour real cargo already in a courier's hands before reserving chest stock.
            for(var o:v.state.orders)if(o.open()&&!o.returnCargo()&&!o.localOnly&&o.kind()==material&&!o.carrier.isEmpty()){
                for(var r:active)if(o.carrier.equals(r.id.toString())){
                    int n=Math.min(o.outstanding(),Math.min(o.inTransit,bags.getOrDefault(r.id,0)));
                    out.put(o,new Allocation(0,Map.of(r.id,n)));bags.merge(r.id,-n,Integer::sum);
                }
            }
            for(var o:v.state.orders)if(o.open()&&!o.returnCargo()&&!o.localOnly&&o.kind()==material){
                var prior=out.getOrDefault(o,new Allocation(0,Map.of()));
                int left=o.outstanding()-prior.total(),stored=Math.min(chest,left);chest-=stored;left-=stored;
                var granted=new LinkedHashMap<UUID,Integer>(prior.bags());
                for(var r:active)if(!r.id.toString().equals(o.recipient)){
                    int n=Math.min(left,bags.getOrDefault(r.id,0));left-=n;bags.merge(r.id,-n,Integer::sum);
                    if(n>0)granted.merge(r.id,n,Integer::sum);
                }
                out.put(o,new Allocation(stored,Map.copyOf(granted)));
            }
        }
        return out;
    }

    /** Free stock / personal emergency kit / reserved for an order / cargo in transit. */
    record Ledger(int free,int kit,int reserved,int transit,int backpacks,int reservedBackpacks){
        int total(){return free+kit+reserved+transit+backpacks;}
        /** Transferable cargo is in a bag, including the portion reserved for an order. */
        boolean strandedInBackpacks(){return backpacks+reservedBackpacks>0;}
    }

    // 256 building projects plus return cargo; admission is explicit and never trims live orders.
    static final int MAX_ORDERS=4096;
    static final int MAX_PURPOSE_LENGTH=128;
    /** A cold trail stops reserving material once the drop can no longer be found. */
    static final long RETURN_CARGO_TICKS=24000;

    private Orders(){}

    private record BedWork(String recipient,long work) { }

    /** Reconcile physical stock, once per recipient/material, without crediting the same
     * items to several projects. A prepared bed covers both of its ingredient orders. */
    static boolean refreshStock(Village v){
        var stock=new HashMap<String,EnumMap<Material,Integer>>();
        var recipients=new HashSet<String>();for(var o:v.state.orders)if(!o.returnCargo())recipients.add(o.recipient);
        for(var r:v.workers.values())if(recipients.contains(r.id.toString())&&r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE&&r.body().isAlive()){
            var amounts=new EnumMap<Material,Integer>(Material.class);
            var bag=r.bot().inventory().snapshot().slots();
            for(var material:Material.values())amounts.put(material,Supplies.count(bag,material.accepts));
            // Three differently coloured single wool blocks cannot make a bed.
            var colours=new HashMap<Item,Integer>();
            for(var s:bag)if(s.is(ItemTags.WOOL))colours.merge(s.getItem(),s.getCount(),Integer::sum);
            amounts.put(Material.WOOL,colours.values().stream().mapToInt(n->n/3*3).sum());
            stock.put(r.id.toString(),amounts);
        }
        var beds=new HashMap<BedWork,Integer>();var missingBeds=new HashMap<Long,Integer>();boolean changed=false;
        for(var order:v.state.orders)if(!order.returnCargo()&&order.kind()!=null){
            boolean bedIngredient=order.purpose.equals("bed_planks")||order.purpose.equals("bed_wool");
            if(bedIngredient){
                int missing=missingBeds.computeIfAbsent(order.work,work->v.state.buildings.stream().filter(b->b.origin==work)
                        .flatMap(b->b.blocks.stream()).filter(p->p.block().getBlock() instanceof net.minecraft.world.level.block.BedBlock&&!p.matches(v))
                        .mapToInt(p->1).sum());
                int target=3*missing;
                if(order.quantity!=target){order.quantity=target;order.delivered=Math.min(order.delivered,target);changed=true;}
            }
            var bag=stock.get(order.recipient);
            // An unloaded recipient is unknown, not a confirmed loss of his inventory.
            if(bag==null)continue;
            int prepared=0;
            if(bedIngredient){
                var work=new BedWork(order.recipient,order.work);
                var allocated=beds.get(work);
                if(allocated==null){
                    allocated=Math.min(bag.getOrDefault(Material.BEDS,0),(order.quantity+2)/3);
                    bag.merge(Material.BEDS,-allocated,Integer::sum);beds.put(work,allocated);
                }
                prepared=Math.min(order.quantity,3*allocated);
            }
            int held=Math.min(order.quantity-prepared,bag.getOrDefault(order.kind(),0));
            bag.merge(order.kind(),-held,Integer::sum);
            order.craftedCoverage=prepared;
            int covered=prepared+held;
            if(order.delivered!=covered){order.delivered=covered;changed=true;}
            int moving=Math.min(order.inTransit,order.outstanding());
            if(order.inTransit!=moving){order.inTransit=moving;order.transit=moving>0;changed=true;}
        }
        return changed;
    }

    /**
     * Splits one material class across the village. Bags and kits are positional so a caller
     * can pass only the residents that are actually loaded and active.
     */
    static Ledger split(List<Order> orders,Material material,int chest,int[] bags,int[] kits){
        int kit=0,surplus=0;
        for(int i=0;i<bags.length;i++){
            int held=Math.max(0,bags[i]),keep=Math.min(held,Math.max(0,kits[i]));
            kit+=keep;surplus+=held-keep;
        }
        int availableChest=Math.max(0,chest),availableBags=surplus,reserved=0,transit=0,reservedBags=0;
        for(Order order:orders)if(order.open()&&!order.localOnly&&order.kind()==material&&!order.returnCargo()){
            int moving=Math.min(availableBags,Math.min(order.outstanding(),order.inTransit));
            transit+=moving;availableBags-=moving;
            int wanted=order.outstanding()-moving;
            int stored=Math.min(availableChest,wanted);availableChest-=stored;wanted-=stored;
            int carried=Math.min(availableBags,wanted);availableBags-=carried;
            reserved+=stored+carried;reservedBags+=carried;
        }
        return new Ledger(availableChest,kit,reserved,transit,availableBags,reservedBags);
    }

    /** The builder orders two beds; the planner reveals the wool, planks and missing tools. */
    static List<Demand> beds(int beds,boolean hasShears,boolean hasIron){
        List<Demand> demand=new ArrayList<>();
        demand.add(new Demand(Material.PLANKS,"bed_planks",3*beds));
        demand.add(new Demand(Material.WOOL,"bed_wool",3*beds));
        if(!hasShears){
            demand.add(new Demand(Material.SHEARS,"bed_shears",1));
            if(!hasIron)demand.add(new Demand(Material.IRON,"bed_shears_iron",2));
        }
        return demand;
    }

    /** Applies one derived need set to the dependent work; reports whether anything changed. */
    static boolean apply(List<Order> orders,long work,List<Demand> demand,String recipient,long delivery,long now){
        return applyScoped(orders,work,demand,recipient,delivery,now,"bed_");
    }
    static boolean applyScoped(List<Order> orders,long work,List<Demand> demand,String recipient,long delivery,long now,String prefix){
        long additions=demand.stream().filter(need->orders.stream().noneMatch(o->!o.returnCargo()&&o.work==work&&o.purpose.equals(need.purpose()))).count();
        if(orders.size()+additions>MAX_ORDERS)throw new IllegalStateException("Лимит заказов: новые заявки ожидают места; действующие сохранены");
        boolean changed=orders.removeIf(o->o.work==work&&!o.returnCargo()&&o.purpose.startsWith(prefix)
                &&demand.stream().noneMatch(need->need.purpose().equals(o.purpose)));
        for(Demand need:demand){
            Order order=orders.stream().filter(o->o.work==work&&o.purpose.equals(need.purpose())).findFirst().orElse(null);
            if(order==null){
                order=new Order();order.work=work;order.purpose=need.purpose();order.created=now;orders.add(order);changed=true;
            }
            if(!order.material.equals(need.material().name())){order.material=need.material().name();changed=true;}
            if(order.quantity!=need.quantity()){order.quantity=need.quantity();order.delivered=Math.min(order.delivered,order.quantity);changed=true;}
            if(!order.recipient.equals(recipient)){order.recipient=recipient;order.delivered=0;release(order);changed=true;}
            if(order.delivery!=delivery){order.delivery=delivery;changed=true;}
        }
        return changed;
    }

    /** Cancelling a request removes its reserve, so the material becomes free stock again. */
    static boolean cancel(List<Order> orders,long work,String prefix){
        return orders.removeIf(order->order.work==work&&order.purpose.startsWith(prefix));
    }

    static boolean clear(List<Order> orders,String prefix){
        return orders.removeIf(order->order.purpose.startsWith(prefix));
    }

    static void release(Order order){order.transit=false;order.inTransit=0;order.carrier="";}

    /** Cancel the lost recipient's requests; only physical backpack contents become return cargo. */
    static boolean workerLost(List<Order> orders,UUID id,long meeting,long now,
                              Map<Material,Integer> cargo,Map<Material,Integer> stock){
        String lost=id.toString();
        boolean changed=orders.removeIf(o->lost.equals(o.recipient));
        for(Order order:orders)if(lost.equals(order.carrier)){release(order);changed=true;}
        for(var entry:cargo.entrySet())if(entry.getValue()>0){
            if(orders.size()>=MAX_ORDERS)throw new IllegalStateException("Лимит учёта возвратного груза");
            var order=new Order();order.material=entry.getKey().name();order.purpose="return_cargo";
            order.quantity=entry.getValue();order.delivery=meeting;order.work=meeting;order.created=now;
            order.observedStock=stock.getOrDefault(entry.getKey(),0);orders.add(order);changed=true;
        }
        return changed;
    }

    /** Credit each newly observed stored item once, oldest cargo first, including partial returns. */
    static boolean recovered(List<Order> orders,Material material,int chest,long now){
        var returns=orders.stream().filter(o->o.returnCargo()&&o.kind()==material).toList();
        if(returns.isEmpty())return false;
        int previous=returns.getFirst().observedStock,arrived=Math.max(0,chest-previous);
        boolean changed=false;
        for(Order order:returns){
            int amount=Math.min(arrived,order.outstanding());order.delivered+=amount;arrived-=amount;
            changed|=amount>0||order.observedStock!=chest;order.observedStock=chest;
        }
        return orders.removeIf(o->o.returnCargo()&&o.kind()==material
                &&(!o.open()||now-o.created>RETURN_CARGO_TICKS))||changed;
    }
}
