package com.vlcplayer.app.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {HistoryItem.class, BookmarkItem.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;
    public abstract AppDao dao();

    public static synchronized AppDatabase get(Context ctx) {
        if (instance == null) {
            instance = Room.databaseBuilder(ctx.getApplicationContext(),
                AppDatabase.class, "vlcplayer.db")
                .fallbackToDestructiveMigration()
                .build();
        }
        return instance;
    }
}
