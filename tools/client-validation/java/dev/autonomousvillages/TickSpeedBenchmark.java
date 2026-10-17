package dev.autonomousvillages;

import java.util.*;
import net.minecraft.server.MinecraftServer;

/** Wall-clock throughput measurement in the normal disposable colony scene. */
final class TickSpeedBenchmark {
    private static final int[] TARGETS = Arrays.stream(System.getProperty("avillage.test.benchmarkRates","40,100,200,400,800,1600,4000,10000,0,40").split(",")).mapToInt(Integer::parseInt).toArray();
    private static final int SAMPLE_SECONDS = Math.clamp(Integer.getInteger("avillage.test.benchmarkSeconds",20),5,30);
    private final List<Map<String,Object>> results = new ArrayList<>();
    private final long[] tickTimes = new long[220000];
    private final List<Double> secondRates = new ArrayList<>();
    private int phase = -1, sampleStartTick, binStartTick, sampleCount;
    private long warmUntil, sampleStart, binStart, totalTickNanos, lastProgress;
    private double confirmationTarget = 40;
    private boolean measuring;
    private int target() { return phase < TARGETS.length ? TARGETS[phase] : (int)confirmationTarget; }
    private String name() { return phase == TARGETS.length ? "confirmation" : phase == TARGETS.length-1 && target() == 40 ? "baseline_repeat" : target() == 0 ? "sprint" : "rate"; }
    private static void command(MinecraftServer server,String text) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),text);
    }
    boolean tick(MinecraftServer server,int colonyTicks,Village village,Map<String,Object> evidence) {
        if(phase < 0) {
            if(colonyTicks < 1200) return false;
            evidence.put("performanceBenchmarkOnly",true);
            evidence.put("fullAutonomyAccepted",false);
            evidence.put("tickSpeedBenchmark",results);
            evidence.put("benchmarkProcessors",Runtime.getRuntime().availableProcessors());
            evidence.put("benchmarkMethod","Configurable wall-clock samples after 3s settling; 30s final confirmation; normal client/mods/observer; same evolving disposable world");
            begin(server,evidence);
            return false;
        }
        long now = System.nanoTime();
        if(!measuring) {
            if(now < warmUntil) return false;
            measuring=true;sampleStart=binStart=now;sampleStartTick=binStartTick=server.getTickCount();
            sampleCount=0;totalTickNanos=0;secondRates.clear();
            return false;
        }
        long[] nativeTimes = server.getTickTimesNanos();
        long tickNanos = nativeTimes[Math.floorMod(server.getTickCount()-1,nativeTimes.length)];
        if(sampleCount < tickTimes.length) tickTimes[sampleCount]=tickNanos;
        sampleCount++;totalTickNanos+=tickNanos;
        if(now-binStart >= 1_000_000_000L) {
            secondRates.add((server.getTickCount()-binStartTick)*1_000_000_000.0/(now-binStart));
            binStart=now;binStartTick=server.getTickCount();
        }
        if(now-lastProgress >= 5_000_000_000L) {
            lastProgress=now;
            SettlementSimulationProbe.LOG.info("TICK_BENCH progress phase={} target={} actual={} elapsed={}",
                name(),target(),String.format(Locale.ROOT,"%.1f",(server.getTickCount()-sampleStartTick)*1_000_000_000.0/(now-sampleStart)),
                (now-sampleStart)/1_000_000_000L);
        }
        int seconds = phase == TARGETS.length ? 30 : SAMPLE_SECONDS;
        if(now-sampleStart < seconds*1_000_000_000L) return false;
        double elapsed=(now-sampleStart)/1_000_000_000.0;
        double actual=(server.getTickCount()-sampleStartTick)/elapsed;
        long[] sorted=Arrays.copyOf(tickTimes,Math.min(sampleCount,tickTimes.length));Arrays.sort(sorted);
        var bins=new ArrayList<>(secondRates);Collections.sort(bins);
        double p10=bins.isEmpty()?actual:bins.get(Math.min(bins.size()-1,(int)Math.floor((bins.size()-1)*.1)));
        var jobs=new TreeMap<String,Integer>();
        for(var r:village.workers.values())jobs.merge(r.job==null?"idle":r.job,1,Integer::sum);
        var row=new LinkedHashMap<String,Object>();
        row.put("phase",name());row.put("targetTps",target()==0?"unlimited":target());
        row.put("actualTps",actual);row.put("oneSecondP10Tps",p10);row.put("elapsedSeconds",elapsed);
        row.put("measuredTicks",server.getTickCount()-sampleStartTick);
        row.put("meanTickMs",totalTickNanos/(double)Math.max(1,sampleCount)/1_000_000);
        row.put("p95TickMs",sorted[Math.min(sorted.length-1,(int)Math.ceil(sorted.length*.95)-1)]/1_000_000.0);
        row.put("colonyTicks",colonyTicks);row.put("residents",village.state.residents.size());row.put("jobs",jobs);
        row.put("heapUsedMiB",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576);
        row.put("projects",village.state.buildings.stream().filter(b->!b.completed).map(b->Map.of("kind",b.kind,"built",b.built(village),"total",b.blocks.size())).toList());
        results.add(row);
        SettlementSimulationProbe.LOG.info("TICK_BENCH RESULT {}",VillageRuntime.GSON.toJson(row));
        if(target()==0) confirmationTarget=Math.clamp(Math.floor(Math.min(actual,p10)*.8/5)*5,1,10000);
        if(phase==TARGETS.length) {
            evidence.put("benchmarkConfirmationTarget",confirmationTarget);
            evidence.put("benchmarkCompleted",true);
            command(server,"tick sprint stop");command(server,"tick rate 40");
            return true;
        }
        begin(server,evidence);
        return false;
    }
    private void begin(MinecraftServer server,Map<String,Object> evidence) {
        phase++;measuring=false;
        if(server.tickRateManager().isSprinting())command(server,"tick sprint stop");
        if(target()==0)command(server,"tick sprint 1000000");else command(server,"tick rate "+target());
        evidence.put("simulationTickRate",target()==0?"unlimited":target());evidence.put("benchmarkSampleSeconds",SAMPLE_SECONDS);
        evidence.put("benchmarkCurrentPhase",name());evidence.put("benchmarkCurrentTarget",target()==0?"unlimited":target());
        warmUntil=System.nanoTime()+3_000_000_000L;lastProgress=System.nanoTime();
        SettlementSimulationProbe.LOG.info("TICK_BENCH START phase={} target={}",name(),target());
    }
}
