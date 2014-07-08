package de.measite.contactmerger.ui;

import java.util.ArrayList;
import java.util.HashSet;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.Log;

import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.log.Database;

public class SeparateThread extends Thread {

    protected final static String[] CONTACTS_RAW_CONTACT_ID_PROJECTION =
        new String[]{
            ContactsContract.RawContacts._ID,
        };
    protected final static String[] CONTACTS_AGGEX_TYPE_PROJECTION =
            new String[]{
                    ContactsContract.AggregationExceptions.TYPE
            };
    protected final Context context;
    protected final ContactDataMapper mapper;
    protected long[] id;
    protected ContentProviderClient contactsProvider;
    protected long root;

    public SeparateThread(Context context, ContentProviderClient contactsProviderClient, ContactDataMapper mapper, long root, long... id) {
        this.contactsProvider = contactsProviderClient;
        this.context = context;
        this.mapper = mapper;
        this.id = id;
        this.root = root;
    }

    @Override
    public void run() {
        // give the UI some time to show up.
        try {
            Thread.sleep(100);
        } catch (Exception e) { /* nothing to do */ }

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
        try {
            c.close();
        } catch (Exception e) { /* No action required */ }
        if (rootRaw.size() == 0) {
            Log.d("SeparateThread", "Could not resolve contact " + root);
            return;
        } else {
            Log.d("SeparateThread", "Contact " + root + " has " + rootRaw.size() + " raw contacts");
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
            try {
                c.close();
            } catch (Exception e) { /* No action required */ }
            if (raw.size() == 0) {
                Log.d("SeparateThread", "Could not resolve contact " + i);
            } else {
                Log.d("SeparateThread", "Contact " + i + " has " + raw.size() + " raw contacts");
            }
        }

        ArrayList<Database.Change> changes = new ArrayList<>();
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        for (int i = 0; i < rootRaw.size(); i++) {
            long a = rootRaw.get(i);

            for (int j = 0; j < raw.size(); j++) {
                long b = raw.get(j);


                long ida = Math.min(a, b);
                long idb = Math.max(a, b);

                Log.d("SeperateThread", "Separate RawContacts " + ida + "+" + idb);

                int oldValue = mapper.getAggregationMode(ida, idb);
                if (oldValue == ContactsContract.AggregationExceptions.TYPE_AUTOMATIC) {
                    oldValue = mapper.getAggregationMode(idb, ida);
                }

                Database.Change change = new Database.Change();
                change.rawContactId1 = ida;
                change.rawContactId2 = idb;
                change.oldValue = oldValue;
                change.newValue = ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE;
                changes.add(change);

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
                if (ops.size() >= 10) {
                    // small batches or we may stall the UI (ANR)
                    try {
                        contactsProvider.applyBatch(ops);
                    } catch (OperationApplicationException e) {
                        e.printStackTrace();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    ops.clear();
                    try {
                        Thread.sleep(1);
                    } catch (Exception e) { /* nothing to do */ }
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
        Contact contact = mapper.getContactById(root, false, false);
        HashSet<String> names = new HashSet<>((1 + id.length) * 2 + 1);
        names.add(contact.getDisplayName().toLowerCase());
        StringBuilder sb = new StringBuilder(contact.getDisplayName());
        for (long i : id) {
            contact = mapper.getContactById(i, false, false);
            if (contact == null) continue;
            if (names.contains(contact.getDisplayName().toLowerCase())) {
                continue;
            }
            names.add(contact.getDisplayName().toLowerCase());
            sb.append(", ");
            sb.append(contact.getDisplayName());
        }
        Database.log(context, "Separate " + sb.toString(), changes.toArray(new Database.Change[changes.size()]));
    }

}
