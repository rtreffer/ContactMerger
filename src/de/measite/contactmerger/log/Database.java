package de.measite.contactmerger.log;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.ArrayList;

/**
 * Action log database, will allow undo operations.
 */
public class Database {

    private static class MyDatabaseHelper extends SQLiteOpenHelper {
        public MyDatabaseHelper(Context context) {
            super(context, "log", null, 1);
        }
        @Override
        public void onCreate(SQLiteDatabase db) {
            try {
                db.execSQL("CREATE TABLE actionlog(" +
                                "_id INTEGER PRIMARY KEY," +
                                "timestamp INTEGER," +
                                "description TEXT," +
                                "actiontype INTEGER," +
                                "undone INTEGER" +
                                ")"
                );
                db.execSQL("CREATE TABLE actionchanges(" +
                                "_id INTEGER PRIMARY KEY," +
                                "actionLogId INTEGER," +
                                "rawContact1 INTEGER," +
                                "rawContact2 INTEGER," +
                                "oldValue INTEGER," +
                                "newValue INTEGER" +
                                ")"
                );
                db.execSQL("CREATE INDEX actions_by_time ON actionlog ( timestamp DESC );");
                db.execSQL("CREATE INDEX changes_by_action ON actionchanges ( actionLogId ASC );");
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }
        }
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    public final static String[] actionlogProjection = new String[]{
            "_id", "timestamp", "description", "actiontype", "undone"
    };

    public final static String[] actionProjection = new String[]{
            "_id", "actionLogId", "rawContact1", "rawContact2", "oldValue", "newValue"
    };

    public final static int MERGE = 1;
    public final static int SEPARATE = 1;

    private static SQLiteDatabase db = null;

    private final static Uri URI = Uri.parse("content://" + Database.class.getCanonicalName() + "/actions");

    public static class Change {
        public long rawContactId1;
        public long rawContactId2;
        public int oldValue;
        public int newValue;
    }

    public static synchronized void setUndone(Context context, long actionid) {
        if (db == null) {
            db = new MyDatabaseHelper(context).getWritableDatabase();
        }

        ContentValues values = new ContentValues();
        values.put("undone", 1);

        db.update("actionlog", values, "_id=" + actionid, null);

        context.getContentResolver().notifyChange(URI, null);
    }

    public static synchronized long log(Context context, String description, Change changes[]) {
        if (db == null) {
            db = new MyDatabaseHelper(context).getWritableDatabase();
        }

        if (changes.length == 0) {
            // we need an action in this case 0.o
            Log.d("ActionDatabase", "log without actions: " + description);
            return -1;
        }

        int action = -1;
        if (changes[0].newValue == ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER) {
            action = MERGE;
        }
        if (changes[0].newValue == ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE) {
            action = SEPARATE;
        }

        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("timestamp", now);
        values.put("actiontype", 1);
        values.put("undone", false);
        values.put("description", description);

        long id = db.insert("actionlog", null, values);

        values.clear();
        values.put("actionLogId", id);
        for (Change c : changes) {
            values.put("rawContact1", c.rawContactId1);
            values.put("rawContact2", c.rawContactId2);
            values.put("oldValue", c.oldValue);
            values.put("newValue", c.newValue);
            db.insert("actionchanges", null, values);
        }

        context.getContentResolver().notifyChange(URI, null);
        return id;
    }

    public static synchronized Cursor query(Context context) {
        if (db == null) {
            db = new MyDatabaseHelper(context).getWritableDatabase();
        }

        Cursor cursor = db.query("actionlog", actionlogProjection, null, null, null, null, "timestamp DESC");
        cursor.setNotificationUri(context.getContentResolver(), URI);
        return cursor;
    }

    public static synchronized Change[] getChanges(Context context, long actionid) {
        if (db == null) {
            db = new MyDatabaseHelper(context).getWritableDatabase();
        }

        ArrayList<Change> changes = new ArrayList<>();
        Cursor cursor = db.query("actionchanges", actionProjection, "actionLogId=" + actionid, null, null, null, null);
        int rawContact1Pos = cursor.getColumnIndex("rawContact1");
        int rawContact2Pos = cursor.getColumnIndex("rawContact2");
        int oldValuePos = cursor.getColumnIndex("oldValue");
        int newValuePos = cursor.getColumnIndex("newValue");
        if (cursor.moveToFirst()) while (!cursor.isAfterLast()) {
            Change change = new Change();
            change.rawContactId1 = cursor.getInt(rawContact1Pos);
            change.rawContactId2 = cursor.getInt(rawContact2Pos);
            change.oldValue = cursor.getInt(oldValuePos);
            change.newValue = cursor.getInt(newValuePos);
            changes.add(change);
            cursor.moveToNext();
        }

        return changes.toArray(new Change[changes.size()]);
    }

}
