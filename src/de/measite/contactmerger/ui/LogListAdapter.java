package de.measite.contactmerger.ui;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import de.measite.contactmerger.R;
import de.measite.contactmerger.log.Database;

/**
 * Adapter for the action log list.
 */
public class LogListAdapter extends CursorAdapter {

    protected LayoutInflater layoutInflater;
    protected final static int mergeBGa = Color.rgb(255, 255, 246);
    protected final static int mergeBGb = Color.rgb(248, 248, 255);

    public LogListAdapter(Context c) {
        super(c, Database.query(c), false);
        layoutInflater = LayoutInflater.from(c);
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
        text.setText(description);

        view.setBackgroundColor((cursor.getInt(cursor.getColumnIndex("_id")) % 2 == 0) ? mergeBGa : mergeBGb);
    }

}
