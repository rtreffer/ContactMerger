package de.measite.contactmerger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.Version;

import android.content.ContentProviderClient;
import android.content.Context;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.util.Log;
import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.contacts.ImMetadata;
import de.measite.contactmerger.contacts.Metadata;
import de.measite.contactmerger.contacts.RawContact;
import de.measite.contactmerger.graph.GraphIO;
import de.measite.contactmerger.graph.UndirectedGraph;
import de.measite.contactmerger.ui.GraphConverter;
import de.measite.contactmerger.ui.model.MergeContact;
import de.measite.contactmerger.ui.model.ModelIO;

public class AnalyzerThread extends Thread {

    protected static Thread runner;

    protected static float PHASE1 = 0.09f;
    protected static float PHASE2 = 0.9f;

    protected final static String LOGTAG = "contactmerger.AnalyzerThread";
    protected final File path;

    protected Context context;
    protected PerFieldAnalyzerWrapper analyzer;
    protected Directory dir;
    protected ContactDataMapper mapper;
    protected ArrayList<ProgressListener> listeners = new ArrayList<ProgressListener>(2);
    private boolean stop = false;

    public AnalyzerThread(Context context) {
        super();
        this.context = context;
        HashMap<String, Analyzer> fieldAnalyzers = new HashMap<String, Analyzer>();
        fieldAnalyzers.put("id", new KeywordAnalyzer());
        fieldAnalyzers.put("key_contact", new KeywordAnalyzer());
        analyzer = new PerFieldAnalyzerWrapper(
                new StandardAnalyzer(Version.LUCENE_47), fieldAnalyzers);
        dir = null;
        path = context.getDatabasePath("contactsindex");
        while (!path.exists()) {
            path.mkdirs();
            if (!path.exists()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        try {
            dir = new SimpleFSDirectory(path);
            dir.clearLock(IndexWriter.WRITE_LOCK_NAME);
            ContentProviderClient provider =
                    context.getContentResolver().acquireContentProviderClient(
                        ContactsContract.AUTHORITY_URI);
            mapper = new ContactDataMapper(provider);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void addListener(ProgressListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
            listeners.add(listener);
        }
    }

    public void removeListener(ProgressListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public void reportProgress(float progress) {
        synchronized (listeners) {
            for (ProgressListener listener : listeners) {
                listener.update(progress);
            }
        }
    }

    @Override
    public void run() {

        try {

            reportProgress(0f);

            phaseIndex();

            if (stop) return;

            reportProgress(PHASE1);

            UndirectedGraph<Long, Double> graph = phaseAnalyze();

            if (stop) return;

            reportProgress(0.99f);

            File path = context.getDatabasePath("contactsgraph");
            if (!path.exists()) path.mkdirs();

            GraphIO.store(graph, new File(path, "graph.kryo.gz"));

            if (stop) return;

            reportProgress(0.991f);

            File modelFile = new File(path, "model.kryo.gz");
            ContentProviderClient provider =
                    context.getContentResolver().acquireContentProviderClient(
                        ContactsContract.AUTHORITY_URI);
            ArrayList<MergeContact> model = GraphConverter.convert(graph, provider);

            if (stop) return;

            reportProgress(0.996f);

            try {
                ModelIO.store(model, modelFile);
                if (stop) return;
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

        } catch (IOException e) {
            stop = true;
            e.printStackTrace();
        } catch (Exception e) {
            // we should never fail
            stop = true;
            e.printStackTrace();
        } finally {
            if (dir != null) {
                try {
                    dir.close();
                    dir = null;
                } catch (IOException e) {
                } catch (Exception e) {
                }
            }
            if (!stop) reportProgress(1f);
        }
    }

    private UndirectedGraph<Long, Double> phaseAnalyze() throws IOException {
        Log.d(LOGTAG, "All contacts indexed");
        try { Thread.yield(); } catch (Exception e) { /* not critical */ }

        // ok, everything is indexed, now let's build a graph to see
        // how similar the various contacts are....

        int ids[] = mapper.getContactIDs();

        DirectoryReader reader = DirectoryReader.open(dir);
        IndexSearcher search = new IndexSearcher(reader);

        Log.d(LOGTAG, "Indexed docs: " + reader.numDocs());

        UndirectedGraph<Long, Double> graph =
                new UndirectedGraph<Long, Double>();

        BytesRef bytes = new BytesRef(NumericUtils.BUF_SIZE_LONG);

        int done = 0;
        HashMap<String, Integer> words = new HashMap<String, Integer>();
        for (int i = 0; i < reader.maxDoc(); i++) {
            // go through all accounts, again, and check what accounts
            // should be merged

            if (stop) return null;

            Document doc = reader.document(i);
            if (doc == null) continue;

            long id = Long.parseLong(doc.getValues("id")[0]);
            Log.d(LOGTAG, "Analyzing document " + i  + " contact " + id + " " + done + "/" + ids.length);

            // try to find dups based on the "all" field

            try {
                BooleanQuery root = new BooleanQuery();
                TokenStream stream = doc.getField("all").tokenStream(analyzer);
                CharTermAttribute term = stream.getAttribute(CharTermAttribute.class);
                stream.reset();
                words.clear();
                int wordCount = 0;
                while (stream.incrementToken()) {
                    wordCount++;
                    String sterm = term.toString();
                    Integer count = words.get(sterm);
                    if (count == null) {
                        words.put(sterm, 1);
                    } else {
                        words.put(sterm, count + 1);
                    }
                }
                stream.close();

                for (Entry<String, Integer> entry : words.entrySet()) {
                    double frequency = ((double) entry.getValue()) / wordCount;
                    String t = entry.getKey();
                    Query q = null;
                    if (t.length() <= 3) {
                        q = new TermQuery(new Term("all", t));
                    } else if (t.length() <= 6) {
                        q = new FuzzyQuery(new Term("all", t), 1, 2);
                    } else {
                        q = new FuzzyQuery(new Term("all", t), 2, 2);
                    }
                    q.setBoost((float) frequency);
                    root.add(new BooleanClause(q, Occur.SHOULD));
                }
                root.setMinimumNumberShouldMatch(Math.min(
                        words.size(),
                        1 + words.size() * 2 / 3
                ));
                float boost = (float) (
                        0.1 + 0.9 * (words.size() - 1) / words.size()
                );
                root.setBoost(boost);
                NumericUtils.longToPrefixCoded(id, 0, bytes);
                root.add(new BooleanClause(new TermQuery(
                        new Term("id", bytes)),
                        Occur.MUST_NOT
                ));

                TopDocs docs = search.search(root, 20);
                if (docs.scoreDocs.length > 0) {
                    Log.d(LOGTAG, "Reference " + Arrays.toString(doc.getValues("display_name")));
                }
                for (ScoreDoc otherDoc : docs.scoreDocs) {
                    Document other = reader.document(otherDoc.doc);
                    long otherId = Long.parseLong(other.getValues("id")[0]);
                    String log =
                            "Hit: doc " + otherDoc.doc + " / contact " +
                                    otherId + " / score " + otherDoc.score +
                                    " " + Arrays.toString(other.getValues("display_name"));
                    Log.d(LOGTAG, log);
                    Double d = graph.getEdge(id, otherId);
                    if (d == null) {
                        d = 0d;
                    }
                    graph.setEdge(id, otherId, d + otherDoc.score);
                }
            } catch (BooleanQuery.TooManyClauses e) {
                e.printStackTrace();
            }

            try {
                // try to find dups based on the contact field
                BooleanQuery root = new BooleanQuery();
                String contacts[] = doc.getValues("key_contact");
                for (String contact : contacts) {
                    if (contact.length() == 0) continue;
                    Query q = null;
                    if (contact.length() <= 3) {
                        q = new TermQuery(new Term("key_contact", contact));
                    } else if (contact.length() <= 6) {
                        q = new FuzzyQuery(new Term("key_contact", contact), 1, 2);
                    } else {
                        q = new FuzzyQuery(new Term("key_contact", contact), 2, 2);
                    }
                    q.setBoost((contact.length() - 1f) / contact.length());
                    root.add(new BooleanClause(q, Occur.SHOULD));
                }
                root.setMinimumNumberShouldMatch(1);
                root.setBoost(root.clauses().size());
                if (root.getClauses().length > 0) {
                    root.add(new BooleanClause(new TermQuery(
                            new Term("id", bytes)),
                            Occur.MUST_NOT
                    ));
                    TopDocs docs = search.search(root, 10);
                    if (docs.scoreDocs.length > 0) {
                        Log.d(LOGTAG, "Reference [contact methods] " + Arrays.toString(contacts));
                    }
                    for (ScoreDoc otherDoc : docs.scoreDocs) {
                        Document other = reader.document(otherDoc.doc);
                        long otherId = Long.parseLong(other.getValues("id")[0]);
                        String log =
                                "Hit: doc " + otherDoc.doc + " / contact " +
                                        otherId + " / score " + otherDoc.score +
                                        " " + Arrays.toString(other.getValues("display_name"));
                        Log.d(LOGTAG, log);
                        Double d = graph.getEdge(id, otherId);
                        if (d == null) {
                            d = 0d;
                        }
                        graph.setEdge(id, otherId, d + otherDoc.score);
                    }
                }
            } catch (BooleanQuery.TooManyClauses e) {
                e.printStackTrace();
            }

            done++;
            reportProgress(PHASE1 + ((PHASE2 - 0.001f) * Math.min(done, ids.length)) / ids.length);
            try {
                Thread.yield();
            } catch (Exception e) {
                /* not critical */
            }
        }

        return graph;
    }

    private void phaseIndex() throws IOException {
        IndexWriter writer = new IndexWriter(dir,
                new IndexWriterConfig(
                    Version.LUCENE_47,
                    analyzer));

        writer.deleteAll();

        try {
            int[] ids = mapper.getContactIDs();
            Log.d(LOGTAG, "Got " + ids.length + " contacts to index");

            int done = 0;
            for (int i : ids) {

                if (stop) return;

                Contact contact = mapper.getContactById(i, true, true);
                if (contact == null) continue; // modified as we read it :-S
                Document doc = new Document();
                done++;

                doc.add(new LongField("id", contact.getId(), Store.YES));
                String all = "";
                if (!empty(contact.getDisplayName())) {
                    doc.add(new TextField(
                            "display_name", contact.getDisplayName(), Store.YES));
                    all = all + " " + contact.getDisplayName();
                }
                if (!empty(contact.getDisplayNameAlternative())) {
                    doc.add(new TextField(
                            "display_name_alternative", contact.getDisplayNameAlternative(), Store.YES));
                    all = all + " " + contact.getDisplayNameAlternative();
                }
                if (!empty(contact.getDisplayNamePrimary())) {
                    doc.add(new TextField(
                            "display_name_primary", contact.getDisplayNamePrimary(), Store.YES));
                    all = all + " " + contact.getDisplayNameAlternative();
                }
                if (!empty(contact.getPhoneticName())) {
                    doc.add(new TextField(
                            "phonetic_name", contact.getPhoneticName(), Store.YES));
                    all = all + " " + contact.getPhoneticName();
                }
                if (!empty(contact.getSortKeyPrimary())) {
                    doc.add(new TextField(
                            "sort_key_primary", contact.getSortKeyPrimary(), Store.YES));
                    all = all + " " + contact.getSortKeyPrimary();
                }
                if (!empty(contact.getSortKeyAlternative())) {
                    doc.add(new TextField(
                            "sort_key_alternative", contact.getSortKeyAlternative(), Store.YES));
                    all = all + " " + contact.getSortKeyAlternative();
                }

                RawContact[] raws = contact.getRawContacts();
                if (raws == null) continue;

                for (RawContact raw : raws) {
                    if (!empty(raw.getAccountType()) && !empty(raw.getAccountName())) {
                        doc.add(new TextField(
                                "account_type_" + raw.getAccountType(),
                                raw.getAccountName(), Store.YES));
                        doc.add(new TextField(
                                "account_type", raw.getAccountType(), Store.YES));
                        doc.add(new TextField(
                                "account_name", raw.getAccountName(), Store.YES));
                    }
                    for (Metadata data : raw.getMetadata().values()) {
                        if (data.getMimetype().equals(ImMetadata.MIMETYPE)) {
                            if (!empty(data.getData(0))) {
                                doc.add(new TextField(
                                        "im", data.getData(0), Store.YES));
                                doc.add(new TextField(
                                        "key_contact", data.getData(0), Store.YES));
                                all = all + " " + data.getData(2);
                            }
                            if (!empty(data.getData(2))) {
                                doc.add(new TextField(
                                        "im", data.getData(2), Store.YES));
                                doc.add(new TextField(
                                        "key_contact", data.getData(2), Store.YES));
                                all = all + " " + data.getData(2);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(StructuredName.CONTENT_ITEM_TYPE)) {
                            if (!empty(data.getData(0))) {
                                doc.add(new TextField(
                                        "display_name", data.getData(0), Store.YES));
                                all = all + " " + data.getData(0);
                            }
                            if (!empty(data.getData(1))) {
                                doc.add(new TextField(
                                        "given_name", data.getData(1), Store.YES));
                                all = all + " " + data.getData(1);
                            }
                            if (!empty(data.getData(2))) {
                                doc.add(new TextField(
                                        "family_name", data.getData(2), Store.YES));
                                all = all + " " + data.getData(2);
                            }
                            if (!empty(data.getData(4))) {
                                doc.add(new TextField(
                                        "middle_name", data.getData(4), Store.YES));
                                all = all + " " + data.getData(4);
                            }
                            if (!empty(data.getData(6))) {
                                doc.add(new TextField(
                                        "phonetic_given_name", data.getData(6), Store.YES));
                                all = all + " " + data.getData(6);
                            }
                            if (!empty(data.getData(8))) {
                                doc.add(new TextField(
                                        "phonetic_family_name", data.getData(8), Store.YES));
                                all = all + " " + data.getData(8);
                            }
                            if (!empty(data.getData(7))) {
                                doc.add(new TextField(
                                        "phonetic_middle_name", data.getData(7), Store.YES));
                                all = all + " " + data.getData(7);
                            }
                            if (!empty(data.getData(3))) {
                                doc.add(new TextField(
                                        "name_prefix", data.getData(3), Store.YES));
                                all = all + " " + data.getData(3);
                            }
                            if (!empty(data.getData(5))) {
                                doc.add(new TextField(
                                        "name_suffix", data.getData(5), Store.YES));
                                all = all + " " + data.getData(5);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(StructuredPostal.CONTENT_ITEM_TYPE)) {
                            if (!empty(data.getData(0))) {
                                doc.add(new TextField(
                                        "postal", data.getData(0), Store.YES));
                                all = all + " " + data.getData(0);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(Phone.CONTENT_ITEM_TYPE)) {
                            if (!empty(data.getData(0))) {
                                String str = data.getData(0);
                                doc.add(new TextField(
                                        "phonenumber", str, Store.YES));
                                doc.add(new TextField(
                                        "key_contact", str, Store.YES));
                                all = all + " " + data.getData(0);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(Email.CONTENT_ITEM_TYPE)) {
                            if (!empty(data.getData(0))) {
                                doc.add(new TextField(
                                        "email", data.getData(0), Store.YES));
                                doc.add(new TextField(
                                        "key_contact", data.getData(0), Store.YES));
                                all = all + " " + data.getData(0);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(Nickname.CONTENT_ITEM_TYPE)) {
                            if (!empty(data.getData(0))) {
                                doc.add(new TextField(
                                        "nickname", data.getData(0), Store.YES));
                                all = all + " " + data.getData(0);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(Website.CONTENT_ITEM_TYPE)) {
                            if (!empty(data.getData(0))) {
                                doc.add(new TextField(
                                        "website", data.getData(0), Store.YES));
                                doc.add(new TextField(
                                        "key_contact", data.getData(0), Store.YES));
                                all = all + " " + data.getData(0);
                            }
                            continue;
                        }
                        if (data.getMimetype().equals(Note.CONTENT_ITEM_TYPE)) continue;
                        if (data.getMimetype().equals(Photo.CONTENT_ITEM_TYPE)) continue;
                        Log.d(LOGTAG, "MIME " + data.getMimetype());
                    }
                }

                doc.add(new TextField(
                        "all", all.trim(), Store.YES));

                writer.addDocument(doc, analyzer);
                Log.d(LOGTAG, "Got contact " + i + " " + done + "/" + ids.length);
                reportProgress(((PHASE1 - 0.001f) * (Math.min(done, ids.length)) / ids.length));
                try {
                    Thread.yield();
                } catch (Exception e) { /* not critical */ }
            }

            writer.forceMerge(1);
            writer.commit();
        } finally {
            try {
                writer.close(true);
            } catch (Exception e) {}
        }
    }

    private final static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public void doStop() {
        this.stop = true;
        try {
            dir.clearLock(IndexWriter.WRITE_LOCK_NAME);
            dir.close();
            dir = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        File lock = new File(path, "write.lock");
        if (lock.exists()) {
            try {
                lock.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        synchronized (listeners) {
            for (ProgressListener listener : listeners) {
                listener.update(0f);
                listener.abort();
            }
        }
    }
}
