package de.measite.contactmerger.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.Image;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import de.measite.contactmerger.R;
import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.contacts.Metadata;
import de.measite.contactmerger.contacts.PhotoMetadata;
import de.measite.contactmerger.contacts.RawContact;
import de.measite.contactmerger.log.Database;
import de.measite.contactmerger.util.LRU;

/**
 * Adapter for the action log list.
 */
public class LogListAdapter extends CursorAdapter implements View.OnClickListener {

    protected final Context context;
    protected final LayoutInflater layoutInflater;
    protected final static int mergeBGa = Color.rgb(255, 255, 246);
    protected final static int mergeBGb = Color.rgb(248, 248, 255);
    protected final ContactDataMapper mapper;
    protected final LRU<Long, Object> cache;

    public LogListAdapter(Context context) {
        super(context, Database.query(context), true);
        layoutInflater = LayoutInflater.from(context);
        this.context = context;
        this.mapper = new ContactDataMapper(this.context);
        this.cache = new LRU(200);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return layoutInflater.inflate(R.layout.log_list_item, null);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        boolean undo = cursor.getInt(cursor.getColumnIndex("undone")) > 0;
        String description = cursor.getString(cursor.getColumnIndex("description"));
        int type = cursor.getInt(cursor.getColumnIndex("actiontype"));
        long actionId = cursor.getLong(cursor.getColumnIndex("_id"));

        ImageView picture = (ImageView) view.findViewById(R.id.contact_picture);
        ImageButton undoIcon = (ImageButton) view.findViewById(R.id.undo);
        TextView text = (TextView) view.findViewById(R.id.actiontext);

        undoIcon.setVisibility(undo ? View.GONE : View.VISIBLE);

        view.setTag(actionId);

        if (undo) {
            text.setPaintFlags(text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            text.setPaintFlags(text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }

        text.setText(description);

        view.setBackgroundColor((cursor.getInt(cursor.getColumnIndex("_id")) % 2 == 0) ? mergeBGa : mergeBGb);

        undoIcon.setClickable(undo == false);
        undoIcon.setOnClickListener(this);

        if (!cache.containsKey(actionId)) {

            long rawIds[] = Database.getRawContactIds(context, actionId);

            // for every raw id: get the contact, sort by contact id ref count, check for image
            HashMap<Long, Integer> contactCount = new HashMap<>();
            HashMap<Long, Object> bitmapData = new HashMap<>();
            for (long rawId : rawIds) {
                long contactId = mapper.getContactByRawContactID(rawId);
                if (contactId == -1) continue; // contact deleted
                if (contactCount.containsKey(contactId)) {
                    contactCount.put(contactId, contactCount.get(contactId) + 1);
                } else {
                    contactCount.put(contactId, 1);
                }
                if (bitmapData.containsKey(contactId)) continue;
                Contact contact = mapper.getContactById(contactId, true, true);
                boolean found = false;
                if (contact != null) {
                    if (contact.getPhotoThumbnailUri() != null &&
                            contact.getPhotoThumbnailUri().length() > 0) {
                        try {
                            Uri.parse(contact.getPhotoThumbnailUri());
                            bitmapData.put(contactId, contact.getPhotoThumbnailUri());
                            found = true;
                        } catch (Exception e) {
                    /* invalid uri, should never(!) happen */
                            e.printStackTrace();
                        }
                    } else {
                        for (RawContact raw : contact.getRawContacts()) {
                            if (found) break;
                            for (Metadata m : raw.getMetadata().values()) {
                                if (!(m instanceof PhotoMetadata)) continue;
                                PhotoMetadata p = (PhotoMetadata) m;
                                byte data[] = p.getBlob();
                                if (data == null || data.length == 0) continue;
                                try {
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                                    bitmapData.put(contactId, bitmap);
                                    found = true;
                                    break;
                                } catch (Exception e) {
                                }
                            }
                        }
                    }
                }
                if (!found) {
                    bitmapData.put(contactId, "");
                }
            }

            // we have filled bitmapData and can now pick the best image based on the count
            int bestCount = 0;
            for (Map.Entry<Long, Integer> e : contactCount.entrySet()) {
                if (e.getValue() <= bestCount) {
                    continue;
                }
                Object bitmap = bitmapData.get(e.getKey());
                if ("".equals(bitmap)) {
                    continue;
                }
                cache.put(actionId, bitmap);
                bestCount = e.getValue();
            }

            if (!cache.containsKey(actionId)) {
                cache.put(actionId, "");
            }
        }

        // the cache must now contain our data, display
        Object image = cache.get(actionId);
        if (image instanceof Bitmap) {
            picture.setImageBitmap((Bitmap) image);
        }
        if (image instanceof String) {
            if ("".equals(image)) {
                // set empty img
                picture.setImageResource(R.drawable.aosp_ic_contacts_holo_dark);
            } else {
                String plainUri = (String) image;
                Uri uri = Uri.parse(plainUri);
                picture.setImageURI(uri);
            }
        }
    }

    @Override
    public void onClick(View v) {
        View t = v;
        while (t.getParent() != null &&
                t.getId() != R.id.log_item_root &&
                t.getParent() instanceof View) {
            t = (View)t.getParent();
        }
        long id = (Long)t.getTag();
        new UndoThread(context, mapper, id).start();
    }

}
