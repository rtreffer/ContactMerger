package de.measite.contactmerger.ui;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentProviderClient;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import de.measite.contactmerger.R;
import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.contacts.ImMetadata;
import de.measite.contactmerger.contacts.Metadata;
import de.measite.contactmerger.contacts.NicknameMetadata;
import de.measite.contactmerger.contacts.PhotoMetadata;
import de.measite.contactmerger.contacts.RawContact;
import de.measite.contactmerger.ui.model.MergeContact;
import de.measite.contactmerger.ui.model.ModelIO;
import de.measite.contactmerger.ui.model.ModelSavePool;
import de.measite.contactmerger.ui.model.RootContact;
import de.measite.contactmerger.util.ShiftedExpireLRU;

public class MergeListAdapter extends BaseAdapter implements OnClickListener {

    protected static final String TAG = "ContactMerger/MergeListAdapter";
    protected Thread transform;
    protected ArrayList<MergeContact> model;
    protected ContentProviderClient provider;
    protected LayoutInflater layoutInflater;
    protected ContactDataMapper contactMapper;
    protected Activity activity;
    protected Typeface font;
    protected File modelFile;
    protected File tmpModelFile;
    protected long timestamp;
    protected static AtomicLong generation = new AtomicLong();

    public MergeListAdapter(Activity activity) {
        super();
        model = new ArrayList<MergeContact>();

        this.activity = activity;

        update();

        provider =
            activity.getContentResolver().acquireContentProviderClient(
                ContactsContract.AUTHORITY_URI);
        contactMapper = new ContactDataMapper(provider);
        contactMapper.setCache(new ShiftedExpireLRU(5 * 60 * 1000, 100));
        layoutInflater = activity.getLayoutInflater();
        font = Typeface.createFromAsset(
                    activity.getAssets(), "fontawesome-webfont.ttf" );
    }

    public synchronized void update() {
        File path = activity.getDatabasePath("contactsgraph");
        if (path == null) return;
        modelFile = new File(path, "model.kryo.gz");
        timestamp = modelFile.lastModified();
        tmpModelFile = new File(path, "model-tmp-" + timestamp + ".kryo.gz");

        try {
            if (!tmpModelFile.exists() && path.exists()) {
                for (File f : path.listFiles()) {
                    if (f.getName().startsWith("model-tmp-")) f.delete();
                }
                this.model = ModelIO.load(modelFile);
                ModelSavePool.getInstance().update(activity, timestamp, generation.getAndIncrement(), (ArrayList<MergeContact>)model.clone());
            } else {
                this.model = ModelIO.load(tmpModelFile);
            }
            notifyDataSetChanged();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int getCount() {
        return (model == null) ? 0 : model.size();
    }

    @Override
    public Object getItem(int position) {
        return (model == null || position >= model.size()) ? null : model.get(position);
    }

    @Override
    public long getItemId(int position) {
        return (model == null || position >= model.size()) ? 0l : model.get(position).id;
    }

    private static class DisplayMeta implements Comparable<DisplayMeta> {
        public int type = 0;
        public String value;
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + type;
            result = prime * result + ((value == null) ? 0 : value.hashCode());
            return result;
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            DisplayMeta other = (DisplayMeta) obj;
            if (type != other.type)
                return false;
            if (value == null) {
                if (other.value != null)
                    return false;
            } else if (!value.equals(other.value))
                return false;
            return true;
        }
        @Override
        public int compareTo(DisplayMeta another) {
            if (another.type > type) return -1;
            if (another.type < type) return  1;
            if (another.value == null && value == null) return 0;
            if (another.value == null) return -1;
            if (value == null) return 1;
            if (another.value.length() < value.length()) return -1;
            if (another.value.length() > value.length()) return  1;
            return value.compareTo(another.value);
        }
    }

    @SuppressLint("DefaultLocale")
    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (view == null) {
            view = layoutInflater.inflate(R.layout.merge_list_item, null);
            TextView accept = (TextView)view.findViewById(R.id.accept);
            TextView remove = (TextView)view.findViewById(R.id.remove);
            accept.setTypeface(font);
            remove.setTypeface(font);
        }

        view.setTag(position);

        TextView spacer = (TextView)view.findViewById(R.id.contact_tree_spacer);
        ImageView picture = (ImageView)view.findViewById(R.id.contact_picture);
        TextView name = (TextView)view.findViewById(R.id.contactname);
        TextView details = (TextView)view.findViewById(R.id.contactdetails);
        TextView accept = (TextView)view.findViewById(R.id.accept);
        TextView remove = (TextView)view.findViewById(R.id.remove);

        accept.setClickable(true);
        remove.setClickable(true);

        accept.setOnClickListener(this);
        remove.setOnClickListener(this);

        MergeContact mcontact = (position >= model.size()) ? null : model.get(position);
        Contact contact = (mcontact == null) ? null :
                contactMapper.getContactById((int)mcontact.id, true, true);
        if (contact == null || (mcontact instanceof RootContact && ((RootContact)mcontact).contacts.size() == 0)) {
            // should never happen....
            if (position < model.size()) model.remove(position);
            ModelSavePool.getInstance().update(activity, timestamp, generation.getAndIncrement(), (ArrayList<MergeContact>)model.clone());
            notifyDataSetChanged();
            spacer.setVisibility(View.GONE);
            accept.setVisibility(View.GONE);
            remove.setVisibility(View.GONE);
            name.setText("");
            return view;
        }

        boolean isRoot = (mcontact instanceof RootContact);

        spacer.setVisibility(isRoot ? View.GONE : View.VISIBLE);
        accept.setVisibility(isRoot ? View.VISIBLE : View.GONE);
        remove.setVisibility(isRoot ? View.GONE : View.VISIBLE);

        name.setText(contact.getDisplayName());
        name.setTextSize(isRoot ? 20 : 14);
        view.setBackgroundColor(isRoot ? Color.rgb(255, 255, 255) : Color.rgb(250, 250, 250));

        // update picture

        boolean found = false;
        if (contact.getPhotoThumbnailUri() != null &&
            contact.getPhotoThumbnailUri().length() > 0) {
            picture.setImageURI(Uri.parse(contact.getPhotoThumbnailUri()));
            found = true;
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
                        picture.setImageBitmap(bitmap);
                        Log.e(TAG, "Display user picture for " + contact.getDisplayName());
                        found = true;
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Can't decode image", e);
                    }
                }
            }
        }
        if (!found) {
            picture.setImageResource(R.drawable.aosp_ic_contacts_holo_dark);
        }

        int px = (int)
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    isRoot ? 56 : 40,
                    activity.getResources().getDisplayMetrics());
        MarginLayoutParams params = (MarginLayoutParams) picture.getLayoutParams();
        params.width = px;
        params.height = px;
        px = (int)
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                4,
                activity.getResources().getDisplayMetrics());
        params.bottomMargin = isRoot ? px : 0;
        params.topMargin = isRoot ? px : 0;
        picture.setLayoutParams(params);

        TreeSet<DisplayMeta> data = new TreeSet<DisplayMeta>(); 
        for (RawContact raw : contact.getRawContacts()) {
            for (Metadata m : raw.getMetadata().values()) {
                if (m instanceof NicknameMetadata) {
                    DisplayMeta d = new DisplayMeta();
                    d.type = 1;
                    d.value = ((NicknameMetadata)m).getNickname();
                    data.add(d);
                }
                if (m.getMimetype().equals(Email.CONTENT_ITEM_TYPE)) {
                    DisplayMeta d = new DisplayMeta();
                    d.type = 2;
                    d.value = m.getData(0);
                    data.add(d);
                }
                if (m instanceof ImMetadata) {
                    DisplayMeta d = new DisplayMeta();
                    d.type = 3;
                    StringBuilder sb = new StringBuilder();
                    ImMetadata im = (ImMetadata)m;
                    if (im.getProtocol() == ImMetadata.Protocol.CUSTOM) {
                        sb.append(im.getCustomProtocolLabel());
                    } else {
                        sb.append(im.getProtocol().name().toLowerCase());
                    }
                    sb.append(im.getData(0));
                    d.value = sb.toString();
                    data.add(d);
                }
                if (m.getMimetype().equals(Phone.CONTENT_ITEM_TYPE)) {
                    DisplayMeta d = new DisplayMeta();
                    d.type = 4;
                    d.value = m.getData(0);
                    data.add(d);
                }
                if (m.getMimetype().equals(Website.CONTENT_ITEM_TYPE)) {
                    DisplayMeta d = new DisplayMeta();
                    d.type = 5;
                    d.value = m.getData(0);
                    data.add(d);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        Iterator<DisplayMeta> iter = data.iterator();
        while (sb.length() < 120 && iter.hasNext()) {
            DisplayMeta d = iter.next();
            if (d.value == null) continue;
            if (sb.length() + d.value.length() > 120) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(d.value);
        }
        details.setText(sb.toString());

        return view;
    }

    @Override
    public void onClick(View v) {
        int pos = (Integer)((View)v.getParent()).getTag();

        Log.d("MergeListAdapter", "click " + v.getClass() + "/" + v.getTag());

        if ("accept".equals(v.getTag())) {
            Log.d("MergeListAdapter", "accept " + pos);
            final MergeContact contact = model.get(pos);
            if (contact instanceof RootContact) {
                // go mad and merge, but do so on a different thread
                model.remove(pos);
                while (pos < model.size() && !(model.get(pos) instanceof RootContact)) {
                    model.remove(pos);
                }
                notifyDataSetChanged();
                ModelSavePool.getInstance().update(activity, timestamp, generation.getAndIncrement(), (ArrayList<MergeContact>)model.clone());
                RootContact root = (RootContact) contact;
                long ids[] = new long[root.contacts.size() + 1];
                ids[root.contacts.size()] = root.id;
                for (int i = 0; i < root.contacts.size(); i++) {
                    ids[i] = root.contacts.get(i).id;
                }
                Log.d("MergeListAdapter", "Merging " + ids.length + " contacts");
                new MergeThread(provider, ids).start();
            }
        }

        if ("remove".equals(v.getTag())) {
            Log.d("MergeListAdapter", "remove " + pos);
            final MergeContact contact = model.get(pos);
            new SeperateThread(provider, contact.root.id, contact.id).start();
            model.remove(pos);
            ModelSavePool.getInstance().update(activity, timestamp, generation.getAndIncrement(), (ArrayList<MergeContact>)model.clone());
            contact.root.contacts.remove(contact);
            if (contact.root.contacts.size() == 0) {
                model.remove(contact.root);
            }
            notifyDataSetChanged();
        }

    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

}
