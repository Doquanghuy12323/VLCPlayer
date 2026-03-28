package com.vlcplayer.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AppDao {
    // History
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertHistory(HistoryItem item);

    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT 50")
    List<HistoryItem> getHistory();

    @Query("SELECT * FROM history WHERE uri = :uri LIMIT 1")
    HistoryItem getHistoryByUri(String uri);

    @Query("DELETE FROM history WHERE uri = :uri")
    void deleteHistory(String uri);

    @Query("DELETE FROM history")
    void clearHistory();

    @Query("DELETE FROM history WHERE watchedAt < :cutoff")
    void deleteOldHistory(long cutoff);

    // Bookmarks
    @Insert
    void insertBookmark(BookmarkItem item);

    @Query("SELECT * FROM bookmarks WHERE uri = :uri ORDER BY position ASC")
    List<BookmarkItem> getBookmarks(String uri);

    @Query("DELETE FROM bookmarks WHERE id = :id")
    void deleteBookmark(int id);
}
