package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** What a release ships: the metadata Fabric Loader checks, and the documents assembleDistribution packs. */
class ReleaseMetadataTest {
    /** Gradle runs the tests in the project directory. */
    static final Path ROOT=Path.of("").toAbsolutePath();
    /** Old repository path of each 0.2 document → its path in the archive. */
    static final Map<String,String> MOVED=Map.of("VALIDATION.md","docs/archive/VALIDATION.md","MODDING_PLAN.md","docs/archive/MODDING_PLAN.md",
            "MODLOG.md","docs/archive/MODLOG.md","docs/ROADMAP.md","docs/archive/ROADMAP-0.2.md",
            "docs/STATIC_AUDIT_2026-10-17.md","docs/archive/STATIC_AUDIT_2026-10-17.md");

    static JsonObject modJson() throws Exception {
        try(var in=Files.newBufferedReader(ROOT.resolve("src/main/resources/fabric.mod.json"))){return JsonParser.parseReader(in).getAsJsonObject();}
    }

    /** The paths listed in build.gradle's {@code def releaseDocs = [...]}. */
    static List<String> releaseDocs(String gradle){
        var list=Pattern.compile("def releaseDocs\\s*=\\s*\\[([^\\]]*)\\]").matcher(gradle);
        assertTrue(list.find(),"build.gradle defines releaseDocs");
        var docs=new ArrayList<String>();
        for(var item=Pattern.compile("'([^']+)'").matcher(list.group(1));item.find();)docs.add(item.group(1));
        return docs;
    }

    @Test void navigationAiRangeTakesThe02SeriesFrom022On() throws Exception {
        String range=modJson().getAsJsonObject("depends").get("navigation_ai").getAsString();
        // "<0.3.0" alone would let 0.3.0-dev in: a pre-release sorts below its release.
        assertEquals(">=0.2.2 <0.3.0-",range);
        var accepts=VersionPredicate.parse(range);
        for(String ok:List.of("0.2.2","0.2.3","0.2.10","0.2.3-dev"))assertTrue(accepts.test(Version.parse(ok)),ok);
        for(String no:List.of("0.2.1","0.2.2-dev","0.3.0-dev","0.3.0-alpha.1","0.3.0","1.0.0"))assertFalse(accepts.test(Version.parse(no)),no);
    }

    @Test void theLibraryBuiltAlongsideIsInsideTheRange() throws Exception {
        Path props=ROOT.resolve("../MinecraftmodNavigationAi/gradle.properties").normalize();
        Assumptions.assumeTrue(Files.isRegularFile(props),"library checkout next to the mod");
        String version=Files.readAllLines(props).stream().map(String::strip).filter(l->l.startsWith("version=")).map(l->l.substring(8).strip()).findFirst().orElseThrow();
        String range=modJson().getAsJsonObject("depends").get("navigation_ai").getAsString();
        assertTrue(VersionPredicate.parse(range).test(Version.parse(version)),"navigation-ai "+version+" outside "+range);
    }

    @Test void contactPointsAtTheRepository() throws Exception {
        var contact=modJson().getAsJsonObject("contact");
        assertNotNull(contact,"fabric.mod.json has contact");
        for(String key:List.of("homepage","sources","issues"))
            assertTrue(contact.get(key).getAsString().startsWith("https://github.com/Arthur32132/autonomous-villages"),key);
        assertTrue(contact.get("issues").getAsString().endsWith("/issues"));
    }

    @Test void everyReleaseDocExistsAndNoArchivedOneShips() throws Exception {
        String gradle=Files.readString(ROOT.resolve("build.gradle"));
        var docs=releaseDocs(gradle);
        assertTrue(docs.containsAll(List.of("README.md","LICENSE","CHANGELOG.md","CONTRIBUTING.md","SECURITY.md","docs/PLAN-v0.3.md","docs/ARCHITECTURE.md","tools/soak/README.md")),docs.toString());
        for(String doc:docs){
            assertTrue(Files.isRegularFile(ROOT.resolve(doc)),doc+" missing");
            String path=Path.of(doc).normalize().toString();
            assertFalse(path.startsWith("docs/archive/")||path.startsWith("docs/release/")||MOVED.containsKey(path),doc+" is a 0.2 document");
        }
        // The source archive lists its extra files by name; an archived journal would fail the build there.
        assertFalse(gradle.contains("'MODLOG.md'"));
    }

    @Test void archivedDocsCarryTheBannerAndAreGoneFromTheirOldPlaces() throws Exception {
        assertTrue(Files.isRegularFile(ROOT.resolve("docs/archive/README.md")));
        for(var move:MOVED.entrySet()){
            var lines=Files.readAllLines(ROOT.resolve(move.getValue()));
            assertTrue(lines.getFirst().startsWith("> **Архив")||lines.getFirst().startsWith("> **Archived"),move.getValue()+": "+lines.getFirst());
            // A new document may take the old path later, but not the 0.2 one under its old title.
            String title=lines.stream().filter(l->l.startsWith("# ")).findFirst().orElseThrow();
            Path old=ROOT.resolve(move.getKey());
            assertFalse(Files.exists(old)&&Files.readAllLines(old).stream().filter(l->l.startsWith("# ")).findFirst().orElse("").equals(title),move.getKey()+" left at its old path");
        }
    }

    @Test void linksInTheArchitectureGuideAndTheArchiveResolve() throws Exception {
        var link=Pattern.compile("\\]\\(([^)\\s]+)\\)");
        var broken=new ArrayList<String>();
        var files=new ArrayList<Path>(List.of(ROOT.resolve("docs/ARCHITECTURE.md")));
        try(var archive=Files.list(ROOT.resolve("docs/archive"))){archive.filter(p->p.toString().endsWith(".md")).forEach(files::add);}
        for(Path file:files)for(var m=link.matcher(Files.readString(file));m.find();){
            String target=m.group(1).replaceFirst("#.*$","");
            if(target.isEmpty()||target.matches("^[a-z]+:.*"))continue;
            // Archived roadmaps name classes deleted since 0.2; those links are history, not navigation.
            if(file.startsWith(ROOT.resolve("docs/archive"))&&target.endsWith(".java"))continue;
            if(!Files.exists(file.getParent().resolve(target).normalize()))broken.add(ROOT.relativize(file)+" -> "+m.group(1));
        }
        assertEquals(List.of(),broken);
    }
}
