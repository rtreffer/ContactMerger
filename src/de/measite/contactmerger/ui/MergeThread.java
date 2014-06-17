package de.measite.contactmerger.ui;

import java.util.ArrayList;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;

public class MergeThread extends Thread {

    private final static String[] CONTACTS_RAW_CONTACT_ID_PROJECTION =
        new String[]{
            ContactsContract.RawContacts._ID
        };

    private long[] id;
    private ContentProviderClient contactsProvider;

    public MergeThread(ContentProviderClient contactsProviderClient, long ... id) {
        this.contactsProvider = contactsProviderClient;
        this.id = id;
    }

    @Override
    public void run() {
        // 2 step merge...
        // 1. Get all raw contacts for the given contact and
        // 2. Set those as mutually inclusive

        ArrayList<Long> raw = new ArrayList<Long>(4);
        for (long i : id) {
            Cursor c = null;
            try {
                c = contactsProvider.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    CONTACTS_RAW_CONTACT_ID_PROJECTION,
                    ContactsContract.RawContacts.CONTACT_ID + "=" + i, null, null);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            if (c != null && c.moveToFirst()) {
                final int columnIndex = c.getColumnIndex(ContactsContract.RawContacts._ID);
                while (!c.isAfterLast()) {
                    raw.add(c.getLong(columnIndex));
                    c.moveToNext();
                }
            }
            if (raw.size() == 0) {
                Log.d("MergeThread", "Could not resolve contact " + i);
            } else {
                Log.d("MergeThread", "Contact " + i + " has " + raw.size() + " raw contacts");
            }
        }

        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        for (int i = 0; i < raw.size(); i++) {
            long a = raw.get(i);

            for (int j = i + 1; j < raw.size(); j++) {
                long b = raw.get(j);


                long ida = Math.min(a, b);
                long idb = Math.max(a, b);

                Log.d("MergeThread", "Merge RawContacts " + ida + "+" + idb);

                ops.add(ContentProviderOperation.newUpdate(
                    ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(
                    ContactsContract.AggregationExceptions.TYPE,
                    ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID1,
                    ida)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                    idb)
                .build());
                if (ops.size() > 100) {
                    try {
                        contactsProvider.applyBatch(ops);
                    } catch (OperationApplicationException e) {
                        e.printStackTrace();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    ops.clear();
                }
            }
        }
        try {
            contactsProvider.applyBatch(ops);
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

}
