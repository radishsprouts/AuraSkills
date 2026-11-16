package dev.aurelium.auraskills.bukkit.ui;

import dev.aurelium.auraskills.common.ui.ActionBarNoticeBoard;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ActionBarNoticeBoardTest {

    @Test
    void noticeIsActiveUntilExpiry() {
        ActionBarNoticeBoard board = new ActionBarNoticeBoard();
        UUID id = UUID.randomUUID();
        board.post(id, "理由", 1000, 2000);
        assertEquals("理由", board.active(id, 1000));
        assertEquals("理由", board.active(id, 2999));
        assertNull(board.active(id, 3000));
        // 期限切れは取り除かれ、以後も null
        assertNull(board.active(id, 1500));
    }

    @Test
    void newNoticeReplacesOld() {
        ActionBarNoticeBoard board = new ActionBarNoticeBoard();
        UUID id = UUID.randomUUID();
        board.post(id, "古い", 0, 2000);
        board.post(id, "新しい", 500, 2000);
        assertEquals("新しい", board.active(id, 2400));
    }

    @Test
    void noticesAreIndependentPerPlayerAndClearable() {
        ActionBarNoticeBoard board = new ActionBarNoticeBoard();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        board.post(a, "A", 0, 2000);
        assertNull(board.active(b, 100));
        board.clear(a);
        assertNull(board.active(a, 100));
        board.post(b, "B", 0, 2000);
        board.clearAll();
        assertNull(board.active(b, 100));
    }
}
