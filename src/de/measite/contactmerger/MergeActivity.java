package de.measite.contactmerger;

import java.io.File;

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
import android.widget.ViewSwitcher;
import de.measite.contactmerger.ui.MergeListAdapter;

public class MergeActivity extends Activity {

    protected static final String TAG = "ContactMerger/MergeActivity";

    protected ProgressBar progressBar;

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
                return;
            }
            if ("finish".equals(event)) {
                progressBar.setProgress(1000);
                progressBar.setMax(1000);
                progressBar.setVisibility(View.INVISIBLE);
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

        updateList();

        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState);
        }
    }

    public void updateList() {
        progressBar = (ProgressBar)findViewById(R.id.analyze_progress);
        progressBar.setVisibility(View.INVISIBLE);

        ListView list = (ListView)findViewById(R.id.contact_merge_list);
        ViewSwitcher switcher = (ViewSwitcher)findViewById(R.id.switcher);

        Context context = getApplicationContext();
        File path = context.getDatabasePath("contactsgraph");
        File modelFile = new File(path, "model.kryo.gz");

        if (modelFile.exists()) {
            if (switcher.getCurrentView().getId() == R.id.load_text) {
                Log.d(TAG, "Show list");
                switcher.showNext();
            }
            list.setAdapter(this.adapter = new MergeListAdapter(this));
            list.postInvalidate();
        } else {
            if (switcher.getCurrentView().getId() == R.id.contact_merge_list) {
                Log.d(TAG, "Show text");
                switcher.showPrevious();
            }
            switcher.bringChildToFront(findViewById(R.id.load_text));
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
