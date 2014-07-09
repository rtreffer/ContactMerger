package de.measite.contactmerger.ui;

import android.content.Context;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.ArrayList;

import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.log.Database;

/**
 * Execute undo operations.
 */
public class UndoThread extends Thread {
    protected final Context context;
    protected final long actionid;
    protected final ContactDataMapper mapper;
    protected final static String TAG = "ContactMerger/Undo";

    public UndoThread(Context context, ContactDataMapper mapper, long actionid) {
        this.context = context;
        this.actionid = actionid;
        this.mapper = mapper;
    }

    @Override
    public void run() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            /* ignore, not relevant */
        }

        Database.Change changes[] = Database.getChanges(context, actionid);

        if (changes.length == 0) return;

        ArrayList<Database.Change> run2 = new ArrayList<>(changes.length + 1);
        for (Database.Change change : changes) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                /* ignore, not relevant */
            }

            final int current = mapper.getAggregationMode(change.rawContactId1, change.rawContactId2);

            if (current == change.oldValue || current != change.newValue) continue;

            if (current == ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER) {
                Log.d(TAG, "set <" + change.rawContactId1 + "," + change.rawContactId2 + "> to " + ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE);
                mapper.setAggregationMode(
                        change.rawContactId1, change.rawContactId2,
                        ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE);
                if (change.oldValue != ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE) {
                    run2.add(change);
                }
                continue;
            }
            if (current == ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE) {
                Log.d(TAG, "set <" + change.rawContactId1 + "," + change.rawContactId2 + "> to " + change.oldValue);
                mapper.setAggregationMode(
                        change.rawContactId1, change.rawContactId2,
                        change.oldValue);
                continue;
            }
        }

        Database.setUndone(context, actionid);

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            /* ignore, not relevant */
        }

        for (Database.Change change : run2) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                /* ignore, not relevant */
            }
            Log.d(TAG, "set <" + change.rawContactId1 + "," + change.rawContactId2 + "> to " + change.oldValue);
            mapper.setAggregationMode(
                    change.rawContactId1, change.rawContactId2,
                    change.oldValue);
        }
    }
}
