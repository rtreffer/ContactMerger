package de.measite.contactmerger.ui.model;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class ModelSavePool {

    protected static ModelSavePool instance;

    protected ThreadPoolExecutor executor;

    protected AtomicLong timestamp = new AtomicLong();
    protected AtomicLong generation = new AtomicLong();
    protected ReentrantLock writeLock = new ReentrantLock();

    static {
        instance = new ModelSavePool();
    }

    protected ModelSavePool() {
        executor = new ThreadPoolExecutor(0, Runtime.getRuntime().availableProcessors(), 1, TimeUnit.MINUTES, new LinkedBlockingQueue<Runnable>(100));
    }

    public static ModelSavePool getInstance() {
        return instance;
    }

    public void update(final Context context, final long itimestamp, final long igeneration, final ArrayList<MergeContact> model) {
        final File path = context.getDatabasePath("contactsgraph");
        final File tmpModelFile = new File(path, "model-tmp-" + itimestamp + ".kryo.gz");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final byte data[] = ModelIO.generate(model);

                    long t = timestamp.get();
                    if (t < itimestamp) {
                        timestamp.compareAndSet(t, itimestamp);
                        File oldTmpModelFile = new File(path, "model-tmp-" + t + ".kryo.gz");
                        if (oldTmpModelFile.exists()) oldTmpModelFile.delete();
                    }

                    t = timestamp.get();
                    if (t > itimestamp) {
                        if (tmpModelFile.exists()) {
                            tmpModelFile.delete();
                        }
                        return;
                    }

                    if (t != itimestamp) {
                        // concurrent update??
                        update(context, itimestamp, igeneration, model);
                        return;
                    }

                    writeLock.lock();
                    // check the generation
                    if (generation.get() > igeneration) {
                        return;
                    }
                    generation.set(igeneration);
                    DataOutputStream dos = new DataOutputStream(new FileOutputStream(tmpModelFile));
                    dos.write(data);
                    dos.flush();
                    dos.close();

                    if (model.size() == 0) {
                        Intent intent = new Intent("de.measite.contactmerger.ANALYSE");
                        intent.putExtra("event", "finish");
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
                    }
                } catch (Exception e) {
                } finally {
                    try { writeLock.unlock(); } catch (Exception e) {}
                }
            }
        });
    }

}
