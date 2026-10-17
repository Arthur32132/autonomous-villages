package dev.autonomousvillages;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Only immutable JSON crosses threads. A queued old snapshot cannot overwrite a critical save. */
final class FileSnapshotWriter implements AutoCloseable {
    private record Snapshot(long revision,String json) { }
    private final Path file;
    private final Executor executor;
    private final ExecutorService owned;
    private final AtomicLong revision=new AtomicLong();
    private final AtomicReference<Snapshot> pending=new AtomicReference<>();
    private final AtomicBoolean scheduled=new AtomicBoolean();
    private final AtomicReference<IOException> failure=new AtomicReference<>();
    private long committed;
    private String lastJson;
    FileSnapshotWriter(Path file) {
        this.file=file;
        owned=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"autonomous-villages-save");t.setDaemon(true);return t;});
        executor=owned;
    }
    FileSnapshotWriter(Path file,Executor executor){this.file=file;this.executor=executor;owned=null;}
    void periodic(String json){
        pending.set(new Snapshot(revision.incrementAndGet(),json));
        schedule();
    }
    private void schedule(){
        if(scheduled.compareAndSet(false,true))executor.execute(()->{
            try{Snapshot next;while((next=pending.getAndSet(null))!=null)try{commit(next);}catch(IOException error){failure.set(error);}}
            finally{scheduled.set(false);if(pending.get()!=null)schedule();}
        });
    }
    void immediate(String json)throws IOException{commit(new Snapshot(revision.incrementAndGet(),json));}
    private synchronized void commit(Snapshot snapshot)throws IOException{
        if(snapshot.revision<=committed)return;
        if(!snapshot.json.equals(lastJson)){
            Path temp=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(temp,snapshot.json);
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
            lastJson=snapshot.json;
        }
        committed=snapshot.revision;
    }
    IOException takeFailure(){return failure.getAndSet(null);}
    @Override public void close(){pending.set(null);if(owned!=null)owned.shutdown();}
}
