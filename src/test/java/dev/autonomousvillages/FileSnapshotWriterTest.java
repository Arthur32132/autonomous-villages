package dev.autonomousvillages;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class FileSnapshotWriterTest {
    @TempDir Path directory;
    @Test void oldPeriodicSnapshotCannotOverwriteNewCriticalState()throws Exception{
        var tasks=new ArrayList<Runnable>();var file=directory.resolve("state.json");
        try(var writer=new FileSnapshotWriter(file,tasks::add)){
            writer.periodic("old");writer.immediate("release-with-returned-inventory");
            tasks.removeFirst().run();assertEquals("release-with-returned-inventory",Files.readString(file));
        }
    }
    @Test void periodicQueueKeepsOnlyTheLatestImmutableSnapshot()throws Exception{
        var tasks=new ArrayList<Runnable>();var file=directory.resolve("state.json");
        try(var writer=new FileSnapshotWriter(file,tasks::add)){
            writer.periodic("one");writer.periodic("two");writer.periodic("three");
            assertEquals(1,tasks.size());tasks.removeFirst().run();
            assertEquals("three",Files.readString(file));assertFalse(Files.exists(directory.resolve("state.json.tmp")));
        }
    }
    @Test void failedBackgroundWriteIsReportedToTheServer(){
        var tasks=new ArrayList<Runnable>();
        try(var writer=new FileSnapshotWriter(directory.resolve("missing/state.json"),tasks::add)){
            writer.periodic("state");tasks.removeFirst().run();assertNotNull(writer.takeFailure());assertNull(writer.takeFailure());
        }
    }
}
