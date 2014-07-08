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

public class MergeThread extends Thread {

    private final static String[] CONTACTS_RAW_CONTACT_ID_PROJECTION =
        new String[]{
            ContactsContract.RawContacts._ID
        };

    private long[] id;
    private ContentProviderClient contactsProvider;
    protected final Context context;
    protected final ContactDataMapper mapper;

    public MergeThread(Context context, ContentProviderClient contactsProviderClient, ContactDataMapper mapper, long ... id) {
        this.contactsProvider = contactsProviderClient;
        this.context = context;
        this.mapper = mapper;
        this.id = id;
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

        Contact contact;
        HashSet<String> names = new HashSet<>((1 + id.length) * 2 + 1);
        StringBuilder sb = new StringBuilder();
        for (long i : id) {
            contact = mapper.getContactById(i, false, false);
            if (contact != null) {
                if (names.contains(contact.getDisplayName().toLowerCase())) {
                    continue;
                }
                names.add(contact.getDisplayName().toLowerCase());
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(contact.getDisplayName());
            }
        }

        ArrayList<Database.Change> changes = new ArrayList<>();
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
        for (int i = 0; i < raw.size(); i++) {
            long a = raw.get(i);

            for (int j = i + 1; j < raw.size(); j++) {
                long b = raw.get(j);

                long ida = Math.min(a, b);
                long idb = Math.max(a, b);

                Log.d("MergeThread", "Merge RawContacts " + ida + "+" + idb);

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
                    ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID1,
                    ida)
                .withValue(
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                    idb)
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

        Database.log(context, "Merge " + sb.toString(), changes.toArray(new Database.Change[changes.size()]));
    }

}
