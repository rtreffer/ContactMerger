package de.measite.contactmerger.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import de.measite.contactmerger.R;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.log.Database;

/**
 * Adapter for the action log list.
 */
public class LogListAdapter extends CursorAdapter implements View.OnClickListener {

    protected final Context context;
    protected final LayoutInflater layoutInflater;
    protected final static int mergeBGa = Color.rgb(255, 255, 246);
    protected final static int mergeBGb = Color.rgb(248, 248, 255);
    protected final ContactDataMapper mapper;

    public LogListAdapter(Context context) {
        super(context, Database.query(context), true);
        layoutInflater = LayoutInflater.from(context);
        this.context = context;
        this.mapper = new ContactDataMapper(this.context);
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

        ImageButton undoIcon = (ImageButton) view.findViewById(R.id.undo);
        TextView text = (TextView) view.findViewById(R.id.actiontext);

        undoIcon.setEnabled(undo == false);

        view.setTag(cursor.getInt(cursor.getColumnIndex("_id")));

        if (undo) {
            text.setPaintFlags(text.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            text.setPaintFlags(text.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }

        text.setText(description);

        view.setBackgroundColor((cursor.getInt(cursor.getColumnIndex("_id")) % 2 == 0) ? mergeBGa : mergeBGb);

        undoIcon.setClickable(undo == false);
        undoIcon.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        View t = v;
        while (t.getParent() != null &&
                t.getId() != R.id.log_item_root &&
                t.getParent() instanceof View) {
            t = (View)t.getParent();
        }
        int id = (Integer)t.getTag();
        new UndoThread(context, mapper, id).start();
    }

}
