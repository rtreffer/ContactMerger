package de.measite.contactmerger;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;

import com.tundem.aboutlibraries.Libs;
import com.tundem.aboutlibraries.ui.LibsActivity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ViewSwitcher;
import de.measite.contactmerger.ui.MergeListAdapter;

public class MergeActivity extends Activity {

    protected static final String TAG = "ContactMerger/MergeActivity";

    protected ProgressBar progressBar;
    protected TextView loadText;

    protected BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String event = intent.getStringExtra("event");
            if ("start".equals(event)) {
                progressBar.setProgress(0);
                progressBar.setMax(1000);
                progressBar.setVisibility(View.VISIBLE);
                return;
            }
            if ("progress".equals(event)) {
                float f = intent.getFloatExtra("progress", 0f);
                progressBar.setProgress((int)(1000 * f));
                progressBar.setMax(1000);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.postInvalidate();
                loadText.setText("Analyzing your contacts.\nThis can take a few minutes.\n" +
                        ((int)(f * 100)) + "%"
                );
                return;
            }
            if ("finish".equals(event)) {
                progressBar.setProgress(1000);
                progressBar.setMax(1000);
                progressBar.setVisibility(View.GONE);
                updateList();
                return;
            }
        }
    };

    protected MergeListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.merge);

        startService(new Intent(getApplicationContext(), AnalyzerService.class));

        ListView list = (ListView)findViewById(R.id.contact_merge_list);
        list.setAdapter(this.adapter = new MergeListAdapter(this));
        list.postInvalidate();
        updateList();

        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState);
        }
    }

    public void updateList() {
        progressBar = (ProgressBar)findViewById(R.id.analyze_progress);
        progressBar.setVisibility(View.GONE);

        loadText = (TextView) findViewById(R.id.load_text);

        ViewSwitcher switcher = (ViewSwitcher)findViewById(R.id.switcher);
        ViewSwitcher switcher_list = (ViewSwitcher)findViewById(R.id.switcher_list);

        Context context = getApplicationContext();
        File path = context.getDatabasePath("contactsgraph");
        File modelFile = new File(path, "model.kryo.gz");

        if (path.exists() && modelFile.exists()) {
            this.adapter.update();
            while (switcher.getCurrentView().getId() != R.id.switcher_list) {
                switcher.showNext();
            }
            if (adapter.getCount() == 0) {
                while (switcher_list.getCurrentView().getId() != R.id.all_done) {
                    switcher_list.showNext();
                }
            } else {
                while (switcher_list.getCurrentView().getId() != R.id.contact_merge_list) {
                    switcher_list.showPrevious();
                }
            }
            switcher_list.postInvalidate();
        } else {
            if (switcher.getCurrentView().getId() == R.id.contact_merge_list) {
                switcher.showPrevious();
            }
        }
        switcher.postInvalidate();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.merge, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.about) {
            Intent intent = new Intent(getApplicationContext(), LibsActivity.class);
            Field myFields[] = R.string.class.getFields();
            Field baseFields[] = com.tundem.aboutlibraries.R.string.class.getFields();
            ArrayList<String> fields = new ArrayList<String>(myFields.length + baseFields.length + 1);
            for(Field f : myFields) {
                fields.add(f.getName());
            }
            intent.putExtra(Libs.BUNDLE_TITLE, "About ContactMerger/Libraries");
            intent.putExtra(Libs.BUNDLE_FIELDS, fields.toArray(new String[fields.size()]));
            intent.putExtra(Libs.BUNDLE_LICENSE, true);
            intent.putExtra(Libs.BUNDLE_VERSION, true);
            startActivity(intent);
            return true;
        }
        if (id == R.id.analyze_now) {
            Intent intent = new Intent(getApplicationContext(), AnalyzerService.class);
            intent.putExtra("forceRunning", true);
            startService(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState.containsKey("MERGELIST")) {
            ListView list = (ListView)findViewById(R.id.contact_merge_list);
            list.onRestoreInstanceState(savedInstanceState.getParcelable("MERGELIST"));
        }
        updateList();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ListView list = (ListView)findViewById(R.id.contact_merge_list);
        outState.putParcelable("MERGELIST", list.onSaveInstanceState());
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateList();
        LocalBroadcastManager.getInstance(getApplicationContext())
            .registerReceiver(receiver, new IntentFilter("de.measite.contactmerger.ANALYSE"));
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        updateList();
        LocalBroadcastManager.getInstance(getApplicationContext())
            .registerReceiver(receiver, new IntentFilter("de.measite.contactmerger.ANALYSE"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateList();
        LocalBroadcastManager.getInstance(getApplicationContext())
            .registerReceiver(receiver, new IntentFilter("de.measite.contactmerger.ANALYSE"));
    }

    @Override
    protected void onPause() {
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(receiver);
        super.onPause();
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(receiver);
        super.onStop();
    }

}
