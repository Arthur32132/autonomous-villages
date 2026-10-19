package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WrittenBookContent;
import org.junit.jupiter.api.Test;

class ChronicleBookTest {
    private static WrittenBookContent book(String title,String author){
        return new WrittenBookContent(Filterable.passThrough(title),author,0,List.of(Filterable.passThrough(Component.literal("page"))),true);
    }

    @Test void onlyTheVillagesOwnBookIsRewritten(){
        assertTrue(Chronicle.ownBook(book("Летопись Берёзовка",Chronicle.BOOK_AUTHOR)));
        // A player's book on the librarian's lectern stays where it is.
        assertFalse(Chronicle.ownBook(book("Летопись Берёзовка","Steve")));
        assertFalse(Chronicle.ownBook(book("Мой дневник",Chronicle.BOOK_AUTHOR)));
        assertFalse(Chronicle.ownBook((WrittenBookContent)null));
    }
}
