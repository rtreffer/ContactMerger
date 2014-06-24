package de.measite.contactmerger.ui;

import java.util.ArrayList;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;

public class SeperateThread extends Thread {

    protected final static String[] CONTACTS_RAW_CONTACT_ID_PROJECTION =
        new String[]{
            ContactsContract.RawContacts._ID
        };

    protected long[] id;
    protected ContentProviderClient contactsProvider;
    protected long root;

    public SeperateThread(ContentProviderClient contactsProviderClient, long root, long ... id) {
        this.contactsProvider = contactsProviderClient;
        this.id = id;
        this.root = root;
    }

    @Override
    public void run() {
        // 2 step merge...
        // 1. Get all raw contacts for the given contact and
        // 2. Set those as mutually inclusive

        ArrayList<Long> rootRaw = new ArrayList<Long>(4);

        Cursor c = null;
        try {
            c = contactsProvider.query(
                ContactsContract.RawContacts.CONTENT_URI,
                CONTACTS_RAW_CONTACT_ID_PROJECTION,
                ContactsContract.RawContacts.CONTACT_ID + "=" + root, null, null);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        if (c != null && c.moveToFirst()) {
            final int columnIndex = c.getColumnIndex(ContactsContract.RawContacts._ID);
            while (!c.isAfterLast()) {
                rootRaw.add(c.getLong(columnIndex));
                c.moveToNext();
            }
        }
        if (rootRaw.size() == 0) {
            Log.d("MergeThread", "Could not resolve contact " + root);
            return;
        } else {
            Log.d("MergeThread", "Contact " + root + " has " + rootRaw.size() + " raw contacts");
        }

        ArrayList<Long> raw = new ArrayList<Long>(4);
        for (long i : id) {
            c = null;
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
        for (int i = 0; i < rootRaw.size(); i++) {
            long a = rootRaw.get(i);

            for (int j = 0; j < raw.size(); j++) {
                long b = raw.get(j);


                long ida = Math.min(a, b);
                long idb = Math.max(a, b);

                Log.d("MergeThread", "Seperate RawContacts " + ida + "+" + idb);

                ops.add(ContentProviderOperation.newUpdate(
                    ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(
                    ContactsContract.AggregationExceptions.TYPE,
                    ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID1,
                    ida)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                    idb)
                .build());
                ops.add(ContentProviderOperation.newUpdate(
                    ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(
                    ContactsContract.AggregationExceptions.TYPE,
                    ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID1,
                    idb)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                    ida)
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
