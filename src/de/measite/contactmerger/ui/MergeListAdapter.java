package de.measite.contactmerger.ui;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

import android.app.Activity;
import android.content.ContentProviderClient;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import de.measite.contactmerger.R;
import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.contacts.Metadata;
import de.measite.contactmerger.contacts.PhotoMetadata;
import de.measite.contactmerger.contacts.RawContact;
import de.measite.contactmerger.graph.UndirectedGraph;
import de.measite.contactmerger.ui.model.MergeContact;
import de.measite.contactmerger.ui.model.ModelIO;
import de.measite.contactmerger.ui.model.RootContact;

public class MergeListAdapter extends BaseAdapter implements OnClickListener {

    protected static final String TAG = "ContactMerger/MergeListAdapter";
    protected UndirectedGraph<Long, Double> graph;
    protected Thread transform;
    protected ArrayList<MergeContact> model;
    protected ContentProviderClient provider;
    protected LayoutInflater layoutInflater;
    protected ContactDataMapper contactMapper;
    protected Activity activity;
    protected Typeface font;
    protected File modelFile;

    public MergeListAdapter(Activity activity) {
        super();
        model = new ArrayList<MergeContact>();

        this.activity = activity;

        if (model.size() == 0) update();

        provider =
            activity.getContentResolver().acquireContentProviderClient(
                ContactsContract.AUTHORITY_URI);
        contactMapper = new ContactDataMapper(provider);
        layoutInflater = activity.getLayoutInflater();
        font = Typeface.createFromAsset(
                    activity.getAssets(), "fontawesome-webfont.ttf" );
        if (this.model.size() == 0 && graph != null) {
            update(graph);
        }
    }

    public synchronized void update() {
        File path = activity.getDatabasePath("contactsgraph");
        modelFile = new File(path, "model.kryo.gz");

        try {
            this.model = ModelIO.load(modelFile);
            notifyDataSetChanged();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void update(final UndirectedGraph<Long, Double> graph) {
        this.graph = graph;
        if (transform != null) {
            transform.interrupt();
            try {
                transform.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        final Handler h = new Handler();
        final File path = activity.getDatabasePath("contactsgraph");
        Thread t = new Thread() {
            public void run() {
                try {
                    model = GraphConverter.convert(graph, provider);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyDataSetInvalidated();
                        }
                    });
                    File modelFile = new File(path, "model.kryo.gz");
                    ModelIO.store(model, modelFile);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        t.start();
        transform = t;
    }

    @Override
    public int getCount() {
        return (model == null) ? 0 : model.size();
    }

    @Override
    public Object getItem(int position) {
        return (model == null) ? null : model.get(position);
    }

    @Override
    public long getItemId(int position) {
        return (model == null) ? 0l : model.get(position).id;
    }

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

        MergeContact mcontact = model.get(position);
        Contact contact =
                contactMapper.getContactById((int)mcontact.id, true, true);
        if (contact == null) {
            // should never happen....
            model.remove(position);
            try {
                ModelIO.store(model, modelFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            notifyDataSetChanged();
            spacer.setVisibility(View.GONE);
            accept.setVisibility(View.GONE);
            remove.setVisibility(View.GONE);
            name.setText("");;
            return view;
        }

        boolean isRoot = (mcontact instanceof RootContact);

        spacer.setVisibility(isRoot ? View.GONE : View.VISIBLE);
        accept.setVisibility(isRoot ? View.VISIBLE : View.GONE);
        remove.setVisibility(isRoot ? View.GONE : View.VISIBLE);

        name.setText(contact.getDisplayName());

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
                try {
                    ModelIO.store(model, modelFile);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                notifyDataSetChanged();
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
            model.remove(pos);
            try {
                ModelIO.store(model, modelFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
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
