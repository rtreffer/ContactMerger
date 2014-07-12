package de.measite.contactmerger.contacts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.AggregationExceptions;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.StatusUpdates;
import de.measite.contactmerger.contacts.StatusUpdate.Presence;
import de.measite.contactmerger.util.ShiftedExpireLRU;

/**
 * <p>The ContactsDataMapper is responsible for mapping {@link RawContact} and
 * {@link Metadata} and Metadata subclasses.</p>
 */
public class ContactDataMapper {

    /*
     * Having a data mapper has advantages and disadvantages.
     *
     * Advantages:
     * - Central provider interaction
     * - Clear OOP preference for the model
     *
     * Disadvantages:
     * - Structural overhead
     * - Performance penalty
     *
     * Looks like a no-datamapper attitude would be better. Except when you
     * start to look into the contacts api, at which point you'll start to love
     * OOP on top of the API.
     *
     * You have been warned, braindead code ahead.
     */

    /**
     * The contacts content provider client.
     */
    private final ContentProviderClient provider;

    /**
     * An optional cache.
     */
    private Map<MethodCall, Object> cache = null;

    public void setCache(ShiftedExpireLRU cache) {
        this.cache = cache;
    }

    public static class MethodCall {
        public final long created;
        public final String method;
        public final Object[] args;
        public MethodCall(String method, Object ... args) {
            this.method = method;
            this.args = args;
            this.created = System.currentTimeMillis();
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            MethodCall that = (MethodCall) o;

            // Probably incorrect - comparing Object[] arrays with Arrays.equals
            if (!Arrays.equals(args, that.args)) return false;
            if (!method.equals(that.method)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = method.hashCode();
            result = 31 * result + Arrays.hashCode(args);
            return result;
        }
    }

    /**
     * Create a new data mapper on top of a given contacts provider client.
     * @param provider A ContentProviderClient for the Contacts ContentProvider.
     */
    public ContactDataMapper(ContentProviderClient provider) {
        this.provider = provider;
    }

    public ContactDataMapper(Context context) {
        this.provider =
                context.getContentResolver().acquireContentProviderClient(
                        ContactsContract.AUTHORITY_URI);
    }
    /**
     * Perform a set of operations.
     * @param operations A list of pending operations to be executed.
     */
    public void perform(ArrayList<ContentProviderOperation> operations) {
        try {
            provider.applyBatch(operations);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        }
    }

    /**
     * Save a single Contact with the attached Metadata into the phonebook.
     * @param contact The contact to e saved.
     */
    public void persist(RawContact contact) {
        ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();
        persist(contact, operations);
        perform(operations);
    }

    /**
     * Append all operations needed to store the current contact to a set of
     * operations.
     * @param contact The current contact with metadata.
     * @param operations A set of operations to be extended.
     */
    public void persist(RawContact contact, ArrayList<ContentProviderOperation> operations) {
        int operationsStart = operations.size();
        Builder operation;
        if (contact.getID() == -1) {
            operation = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI);
        } else {
            operation = ContentProviderOperation.newUpdate(RawContacts.CONTENT_URI);
            operation.withSelection(RawContacts._ID + "=?", new String[]{Long.toString(contact.getID())});
        }
        ContentValues values = new ContentValues();
        put(values, contact);
        operation.withValues(values);
        operations.add(operation.build());
        for (Metadata data: contact.getMetadata().values()) {
            values.clear();
            put(values, data);
            if (data instanceof DeletedMetadata) {
                operation = ContentProviderOperation.newDelete(Data.CONTENT_URI);
                operation.withValues(values);
                operation.withSelection(Data._ID + "=?", new String[]{Long.toString(contact.getID())});
                operations.add(operation.build());
                continue;
            }
            if (data.getID() == -1) {
                operation = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            } else {
                operation = ContentProviderOperation.newUpdate(Data.CONTENT_URI);
                operation.withSelection(Data._ID + "=?", new String[]{Long.toString(data.getID())});
            }
            if (contact.getID() == -1) {
                operation.withValueBackReference(Data.RAW_CONTACT_ID, operationsStart);
                values.remove(Data.RAW_CONTACT_ID);
            } else {
                values.put(Data.RAW_CONTACT_ID, contact.getID());
            }
            operation.withValues(values);
            operations.add(operation.build());
        }
    }

    /**
     * Save a single status update.
     * @param statusUpdate The status update to be stored.
     */
    public void persist(StatusUpdate statusUpdate) {
        ContentValues values = new ContentValues();
        try {
            put(values, statusUpdate);
            provider.insert(StatusUpdates.CONTENT_URI, values);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * Fetch a users status based on a account and user jid.
     * @param accountJid The account jid (aka your local jid).
     * @param jid The jid in question (aka the remote jid).
     * @return A new StatusUpdate instance, or null.
     */
    public StatusUpdate getStatusUpdate(String accountJid, String jid) {
        // $%&# ok, so status updates will do magic
        // Solution? use the Data magic, and fill the missing blocks.
        // IM_HANDLER and IM_ACCOUNT is virtual anyway....
        try {
            Cursor cursor = provider.query(
                    Data.CONTENT_URI,
                    STATUS_UPDATES_IMPLICIT_PROJECTION_MAP,
                    Data.MIMETYPE + "=? AND " +
                    Data.SYNC2 + "=? AND " +
                    Data.SYNC3 + "=?",
                    new String[]{ImMetadata.MIMETYPE, accountJid, jid},
                    null);
            if (!cursor.moveToFirst()) {
                cursor.close();
                StatusUpdate update = new StatusUpdate();
                update.setImAccount(accountJid);
                update.setImHandle(jid);
                return update;
            }

            StatusUpdate statusUpdate = newStatusUpdate(cursor);
            cursor.close();

            statusUpdate.setImAccount(accountJid);
            statusUpdate.setImHandle(jid);

            return statusUpdate;

        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * <p>Delete a set of contacts based on their id.</p>
     * <p><i>Note:</i> the method used for bulk delete is a group selection
     * based on id (<i>{@ling BaseColumns#_ID} IN (id1, id2, ...)</i>).
     * @param ids The IDs if all users that should be deleted.
     */
    public void bulkDelete(long[] ids) {
        if (ids.length == 0) {
            return;
        }
        StringBuilder where = new StringBuilder();
        where.append(RawContacts._ID);
        where.append(" IN (");
        where.append(Long.toString(ids[0]));
        for (int i = 1; i < ids.length; i++) {
            where.append(',');
            where.append(Long.toString(ids[i]));
        }
        where.append(')');
        try {
            provider.delete(RawContacts.CONTENT_URI, where.toString(), null);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public int[] getContactIDs() {
        int[] result = null;
        try {
            Cursor cursor = provider.query(
                    Contacts.CONTENT_URI,
                    CONTACT_ID_PROJECTION_MAP,
                    null,
                    null,
                    null);
            int count = cursor.getCount();
            result = new int[count];
            int pos = 0;
            try {
                int index = 0;
                if (cursor.moveToFirst()) {
                    index = cursor.getColumnIndex(Contacts._ID);
                    result[pos++] = cursor.getInt(index);
                }
                cursor.moveToNext();
                while (!cursor.isAfterLast()) {
                    result[pos++] = cursor.getInt(index);
                    cursor.moveToNext();
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
        return result;
    }

    private final static String[] CONTACT_OF_RAW_CONTACT_PROJECTION =
            new String[]{RawContacts.CONTACT_ID};

    public int getContactByRawContactID(long id) {
        int result = -1;
        try {
            Cursor cursor = provider.query(
                    RawContacts.CONTENT_URI,
                    CONTACT_OF_RAW_CONTACT_PROJECTION,
                    RawContacts._ID + "=" + id,
                    null,
                    null);
            try {
                if (cursor.moveToFirst()) {
                    result = cursor.getInt(cursor.getColumnIndex(
                                RawContacts.CONTACT_ID));
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return result;
    }

    public Contact getContactById(long id, boolean rawContacts, boolean metadata) {
        Contact contact = null;

        if (cache != null) {
            MethodCall call = new MethodCall("getContactById", id, rawContacts, metadata);
            contact = (Contact)cache.get(call);
            if (contact != null) return contact;
        }

        // step 1, load contact
        try {
            Cursor cursor = provider.query(
                Contacts.CONTENT_URI,
                CONTACT_PROJECTION_MAP, 
                Contacts._ID + "=" + id,
                null,
                null);
            try {
                if (cursor.moveToFirst()) {
                    contact = newContact(cursor);
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        if (contact == null || !rawContacts) {
            return contact;
        }
        try {
            Cursor cursor = provider.query(
                    RawContacts.CONTENT_URI,
                    RAW_CONTACT_PROJECTION_MAP, 
                    RawContacts.CONTACT_ID + "=" + id,
                    null,
                    null);
            try {
                if (cursor.moveToFirst()) {
                    RawContact raw[] = new RawContact[cursor.getCount()];
                    int pos = 0;
                    RawContact rc = newRawContact(cursor);
                    if (metadata) {
                        fetchMetadata(rc);
                    }
                    raw[pos++] = rc;
                    cursor.moveToNext();
                    while (!cursor.isAfterLast()) {
                        rc = newRawContact(cursor);
                        if (metadata) {
                            fetchMetadata(rc);
                        }
                        raw[pos++] = rc;
                        cursor.moveToNext();
                    }
                    contact.setRawContacts(raw);
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        if (contact != null && cache != null) {
            MethodCall call = new MethodCall("getContactById", id, rawContacts, metadata);
            cache.put(call, contact);
        }

        return contact;
    }

    /**
     * Retrieve a single jid as bound by a local account jid, with or without
     * metadata.
     * @param accountJid The local account jid.
     * @param jid The remote jid.
     * @param metadata True if a second fetch for metadata should be done.
     * @return A single contact.
     */
    public RawContact getRawContactByJid(String accountJid, String jid, boolean metadata) {
        RawContact contact = null;
        try {
            Cursor cursor = provider.query(
                    RawContacts.CONTENT_URI,
                    RAW_CONTACT_PROJECTION_MAP, 
                    RawContacts.ACCOUNT_NAME + "=? AND " +
                    RawContacts.SOURCE_ID + "=?",
                    new String[]{accountJid, jid},
                    null);
            try {
                if (cursor.moveToFirst()) {
                    contact = newRawContact(cursor);
                    if (metadata) {
                        fetchMetadata(contact);
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return contact;
    }

    /**
     * Fetch the metadata of a single account. All results will be attached
     * to the contact.
     * @param contact The contact that should be enriched.
     */
    private void fetchMetadata(RawContact contact) {
        try {
            Cursor cursor = provider.query(
                    Data.CONTENT_URI,
                    DATA_PROJECTION_MAP, 
                    Data.RAW_CONTACT_ID + "=?",
                    new String[]{Long.toString(contact.getID())},
                    null);
            try {
                if (cursor.moveToFirst()) {
                    do {
                        contact.setMetadata(newMetadata(cursor));
                    } while (cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * The projection map used to query the Data table for status update
     * information.
     */
    private static String[] STATUS_UPDATES_IMPLICIT_PROJECTION_MAP = new String[]{
        Data._ID,
        // These variables trigger a left join
        StatusUpdates.PRESENCE,
        StatusUpdates.STATUS,
        StatusUpdates.STATUS_TIMESTAMP,
        StatusUpdates.STATUS_RES_PACKAGE,
        StatusUpdates.STATUS_LABEL,
        StatusUpdates.STATUS_ICON,
    };

    private static String[] CONTACT_PROJECTION_MAP = new String[]{
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.DISPLAY_NAME,
        ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Contacts.DISPLAY_NAME_SOURCE,
        ContactsContract.Contacts.PHONETIC_NAME,
        ContactsContract.Contacts.PHONETIC_NAME_STYLE,
        ContactsContract.Contacts.PHOTO_FILE_ID,
        ContactsContract.Contacts.PHOTO_ID,
        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        ContactsContract.Contacts.PHOTO_URI,
        ContactsContract.Contacts.SORT_KEY_ALTERNATIVE,
        ContactsContract.Contacts.SORT_KEY_PRIMARY,
    };

    private static String[] CONTACT_ID_PROJECTION_MAP = new String[]{
        ContactsContract.Contacts._ID
    };

    /**
     * Projection map used for contact fetch.
     */
    private static String[] RAW_CONTACT_PROJECTION_MAP = new String[]{
        RawContacts._ID,
        RawContacts.ACCOUNT_NAME,
        RawContacts.ACCOUNT_TYPE,
        RawContacts.SOURCE_ID,
        RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.SYNC3, RawContacts.SYNC4
    };

    /**
     * Projection map used for metadata fetches.
     */
    private static String[] DATA_PROJECTION_MAP = new String[]{
        Data._ID,
        Data.RAW_CONTACT_ID,
        Data.MIMETYPE,
        Data.SYNC1,  Data.SYNC2,  Data.SYNC3,  Data.SYNC4,
        Data.DATA1,  Data.DATA2,  Data.DATA3,  Data.DATA4,  Data.DATA5,
        Data.DATA6,  Data.DATA7,  Data.DATA8,  Data.DATA9,  Data.DATA10,
        Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14, Data.DATA15
    };

    /**
     * Projection map for raw contact sync fields.
     */
    private static String[] RAW_CONTACTS_SYNC_FIELDS = new String[]{
        RawContacts.SYNC1, RawContacts.SYNC2,  RawContacts.SYNC3,  RawContacts.SYNC4
    };

    /**
     * Proijection map for data sync fields.
     */
    private static String[] SYNC_FIELDS = new String[]{
        Data.SYNC1,  Data.SYNC2,  Data.SYNC3,  Data.SYNC4
    };

    /**
     * Projection map for metadata data fields.
     */
    private static String[] DATA_FIELDS = new String[]{
        Data.DATA1,  Data.DATA2,  Data.DATA3,  Data.DATA4,  Data.DATA5,
        Data.DATA6,  Data.DATA7,  Data.DATA8,  Data.DATA9,  Data.DATA10,
        Data.DATA11, Data.DATA12, Data.DATA13, Data.DATA14
    };

    /**
     * Store all metadata info into a given contentvalues instance.
     * @param values A ContentValues instance.
     * @param metadata The Metadata instance to be saved to ContentValues.
     */
    private void put(ContentValues values, Metadata metadata) {
        if (metadata.getID() > 0) {
            values.put(Data._ID, metadata.getID());
        }
        if (metadata.getRawContactID() > 0) {
            values.put(Data.RAW_CONTACT_ID, metadata.getRawContactID());
        }
        values.put(Data.MIMETYPE, metadata.getMimetype());
        for (int i = 0; i < SYNC_FIELDS.length; i++) {
            values.put(SYNC_FIELDS[i], metadata.getSync(i));
        }
        for (int i = 0; i < DATA_FIELDS.length; i++) {
            values.put(DATA_FIELDS[i], metadata.getData(i));
        }
        values.put(Data.DATA15, metadata.getBlob());
    }

    /**
     * Add all contact data to a content values instance.
     * @param values The ContentValues instance.
     * @param contact The contact to be copied into the values parameter.
     */
    private void put(ContentValues values, RawContact contact) {
        if (contact.getID() > 0) {
            values.put(RawContacts._ID, contact.getID());
        }
        values.put(RawContacts.ACCOUNT_NAME, contact.getAccountName());
        values.put(RawContacts.ACCOUNT_TYPE, contact.getAccountType());
        values.put(RawContacts.SOURCE_ID, contact.getSourceID());
        for (int i = 0; i < RAW_CONTACTS_SYNC_FIELDS.length; i++) {
            values.put(RAW_CONTACTS_SYNC_FIELDS[i], contact.getSync(i));
        }
    }

    /**
     * Add all status update values to a given ContentValues instance.
     * @param values The ContentValues instance.
     * @param statusUpdate The status update to be copied.
     */
    private void put(ContentValues values, StatusUpdate statusUpdate) {
        if (statusUpdate.getDataId() >= 0l) {
            values.put(StatusUpdates.DATA_ID, statusUpdate.getDataId());
        }
        values.put(StatusUpdates.IM_ACCOUNT, statusUpdate.getImAccount());
        values.put(StatusUpdates.IM_HANDLE, statusUpdate.getImHandle());
        values.put(StatusUpdates.STATUS, statusUpdate.getStatus());
        values.put(StatusUpdates.PRESENCE, statusUpdate.getPresence().getValue());
        values.put(StatusUpdates.PROTOCOL, statusUpdate.getProtocol().getValue());
    }

    /**
     * Create a new status update based on the current cursor.
     * @param cursor The current DB cursor.
     * @return A new StatusUpdate instance.
     */
    private StatusUpdate newStatusUpdate(Cursor cursor) {
        StatusUpdate statusUpdate = new StatusUpdate();

        int index = cursor.getColumnIndex(Data._ID);
        statusUpdate.setDataId(cursor.getLong(index));

        index = cursor.getColumnIndex(StatusUpdates.PRESENCE);
        statusUpdate.setPresence(Presence.byPresenceId(cursor.getInt(index)));

        index = cursor.getColumnIndex(StatusUpdates.STATUS);
        statusUpdate.setStatus(cursor.getString(index));

        return statusUpdate;
    }

    private Contact newContact(Cursor cursor) {
        Contact contact = new Contact();

        int index = cursor.getColumnIndex(Contacts._ID);
        contact.setId(cursor.getLong(index));

        index = cursor.getColumnIndex(Contacts.DISPLAY_NAME);
        contact.setDisplayName(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.DISPLAY_NAME_ALTERNATIVE);
        contact.setDisplayNameAlternative(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.DISPLAY_NAME_PRIMARY);
        contact.setDisplayNamePrimary(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.DISPLAY_NAME_SOURCE);
        contact.setDisplayNameSource(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.PHONETIC_NAME_STYLE);
        contact.setPhoneticNameStyle(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.PHONETIC_NAME);
        contact.setPhoneticName(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.PHOTO_FILE_ID);
        contact.setPhotoFileId(cursor.getLong(index));

        index = cursor.getColumnIndex(Contacts.PHOTO_ID);
        contact.setPhotoId(cursor.getLong(index));

        index = cursor.getColumnIndex(Contacts.PHOTO_THUMBNAIL_URI);
        contact.setPhotoThumbnailUri(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.PHOTO_URI);
        contact.setPhotoUri(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.SORT_KEY_ALTERNATIVE);
        contact.setSortKeyAlternative(cursor.getString(index));

        index = cursor.getColumnIndex(Contacts.SORT_KEY_PRIMARY);
        contact.setSortKeyPrimary(cursor.getString(index));

        return contact;
    }

    /**
     * Create a new raw contact for the current cursor.
     * @param cursor The DB cursor, scrolled to the row in question.
     * @return
     */
    private RawContact newRawContact(Cursor cursor) {
        RawContact contact = new RawContact();
        int index = cursor.getColumnIndex(RawContacts._ID);
        contact.setID(cursor.getLong(index));

        index = cursor.getColumnIndex(RawContacts.ACCOUNT_NAME);
        contact.setAccountName(cursor.getString(index));

        index = cursor.getColumnIndex(RawContacts.ACCOUNT_TYPE);
        contact.setAccountType(cursor.getString(index));

        index = cursor.getColumnIndex(RawContacts.SOURCE_ID);
        contact.setSourceID(cursor.getString(index));

        for (int i = 0; i < RAW_CONTACTS_SYNC_FIELDS.length; i++) {
            index = cursor.getColumnIndex(RAW_CONTACTS_SYNC_FIELDS[i]);
            contact.setSync(i, cursor.getString(index));
        }
        return contact;
    }

    /**
     * Create a new Metadata instance based on the current db curser.
     * @param cursor The current Db cursor, scrolled to the metadata in
     *               question.
     * @return A new Metadata instance.
     */
    private Metadata newMetadata(Cursor cursor) {
        /*
         * This method has high anti-pattern potential. Why?
         * It's cross-referencing the whole Metadata inheritance tree.
         *
         * TODO: Move to a factory.
         */
        Metadata metadata = null;
        int index = cursor.getColumnIndex(Data.MIMETYPE);
        String mimetype = cursor.getString(index);
        if (NicknameMetadata.MIMETYPE.equals(mimetype)) {
            metadata = new NicknameMetadata();
        }
        if (metadata == null && ImMetadata.MIMETYPE.equals(mimetype)) {
            metadata = new ImMetadata();
        }
        if (metadata == null && PhotoMetadata.MIMETYPE.equals(mimetype)) {
            metadata = new PhotoMetadata();
        }
        if (metadata == null && StructuredNameMetadata.MIMETYPE.equals(mimetype)) {
            metadata = new StructuredNameMetadata();
        }
        if (metadata == null) {
            metadata = new Metadata();
            metadata.setMimetype(cursor.getString(index));
        }

        index = cursor.getColumnIndex(Data._ID);
        metadata.setID(cursor.getLong(index));

        index = cursor.getColumnIndex(Data.RAW_CONTACT_ID);
        metadata.setRawContactID(cursor.getLong(index));

        for (int i = 0; i < SYNC_FIELDS.length; i++) {
            index = cursor.getColumnIndex(SYNC_FIELDS[i]);
            metadata.setSync(i, cursor.getString(index));
        }
        for (int i = 0; i < DATA_FIELDS.length; i++) {
            index = cursor.getColumnIndex(DATA_FIELDS[i]);
            metadata.setData(i, cursor.getString(index));
        }
        index = cursor.getColumnIndex(Data.DATA15);
        metadata.setBlob(cursor.getBlob(index));

        return metadata;
    }

    public void setAggregationMode(long id1, long id2, int value) {
        try {
            ContentValues values = new ContentValues();
            values.put(AggregationExceptions.TYPE, value);
            values.put(AggregationExceptions.RAW_CONTACT_ID1, id1);
            values.put(AggregationExceptions.RAW_CONTACT_ID2, id2);
            provider.update(
                    AggregationExceptions.CONTENT_URI,
                    values,
                    AggregationExceptions.RAW_CONTACT_ID1 + "=" + id1 + " AND " +
                    AggregationExceptions.RAW_CONTACT_ID2 + "=" + id2,
                    null
            );
            values.put(AggregationExceptions.RAW_CONTACT_ID1, id2);
            values.put(AggregationExceptions.RAW_CONTACT_ID2, id1);
            provider.update(
                    AggregationExceptions.CONTENT_URI,
                    values,
                    AggregationExceptions.RAW_CONTACT_ID1 + "=" + id2 + " AND " +
                    AggregationExceptions.RAW_CONTACT_ID2 + "=" + id1,
                    null
            );
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public int getAggregationMode(long id1, long id2) {
        int result = AggregationExceptions.TYPE_AUTOMATIC;
        try {
            Cursor cursor = provider.query(
                    AggregationExceptions.CONTENT_URI,
                    new String[]{AggregationExceptions.TYPE}, 
                    AggregationExceptions.RAW_CONTACT_ID1 + "=? AND " +
                    AggregationExceptions.RAW_CONTACT_ID2 + "=?",
                    new String[]{Long.toString(id1), Long.toString(id2)},
                    null);
            try {
                if (cursor.moveToFirst()) {
                    result = cursor.getInt(cursor.getColumnIndex(AggregationExceptions.TYPE));
                }
            } finally {
                cursor.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return result;
    }

}
