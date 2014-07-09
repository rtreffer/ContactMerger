package de.measite.contactmerger;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ListView;

import de.measite.contactmerger.R;
import de.measite.contactmerger.ui.LogListAdapter;

public class LogActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.log);

        ListView list = (ListView)findViewById(R.id.action_list);
        list.setAdapter(new LogListAdapter(getBaseContext()));
        list.setEmptyView(findViewById(R.id.no_actions));
    }

}
