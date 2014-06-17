package de.measite.contactmerger.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;

import android.content.ContentProviderClient;
import android.util.Log;
import android.util.SparseArray;
import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.graph.UndirectedGraph;
import de.measite.contactmerger.ui.model.MergeContact;
import de.measite.contactmerger.ui.model.RootContact;

public class GraphConverter {

    public static ArrayList<MergeContact> convert(
        UndirectedGraph<Long, Double> graph,
        ContentProviderClient provider
    ) {
        TreeSet<UndirectedGraph.Edge<Long, Double>> sorted =
                new TreeSet<UndirectedGraph.Edge<Long, Double>>(Collections.reverseOrder());
        sorted.addAll(graph.edgeSet());

        ContactDataMapper mapper = new ContactDataMapper(provider);
        SparseArray<RootContact> roots = new SparseArray<RootContact>();

        for(UndirectedGraph.Edge<Long, Double> edge : sorted) {

            if (edge.e < 3.5) break; // low quality 

            int c1 = edge.a.intValue();
            int c2 = edge.b.intValue();
            if (c1 == c2) {
                continue; // (same contact)
            }

            // different contacts

            Contact left = mapper.getContactById(c1, false, false);
            Contact right = mapper.getContactById(c2, false, false);

            if (left == null) {
                continue;
            }
            if (right == null) {
                continue;
            }

            RootContact root = roots.get((int)left.getId());
            if (root == null) {
                root = roots.get((int)right.getId());
            } else if (roots.get((int)right.getId()) != null) {
                // we have to merge two roots
                RootContact root1 = roots.get((int)left.getId());
                RootContact root2 = roots.get((int)right.getId());
                if (root1 == root2) continue;
                root = (root1.contacts.size() >= root2.contacts.size()) ? root1 : root2;
                root2 = (root1.contacts.size() >= root2.contacts.size()) ? root2 : root1;
                root1 = root;
                for (MergeContact c: root2.contacts) {
                    c.root = root;
                }
                root1.contacts.addAll(root2.contacts);
                roots.put((int)root2.id, root);
            }
            if (root == null) {
                Contact ref = null;
                if (right.getDisplayName().length() > left.getDisplayName().length()) {
                    ref = right;
                } else {
                    ref = left;
                }
                root = new RootContact();
                root.sortName = ref.getSortKeyPrimary();
                root.id = ref.getId();
                roots.put((int)left.getId(), root);
                roots.put((int)right.getId(), root);
            }

            boolean addc1 = true;
            boolean addc2 = true;
            for (MergeContact c: root.contacts) {
                addc1 &= (c.id != c1);
                addc2 &= (c.id != c2);
                if (!addc1 && !addc2) break;
            }
            if (addc1) {
                MergeContact c = new MergeContact();
                c.root = root;
                c.id = c1;
                root.contacts.add(c);
                roots.put((int)c.id, root);
            }
            if (addc2) {
                MergeContact c = new MergeContact();
                c.root = root;
                c.id = c2;
                root.contacts.add(c);
                roots.put((int)c.id, root);
            }
        }

        TreeSet<RootContact> contactRoots = new TreeSet<RootContact>();
        for (int i = 0; i < roots.size(); i++) {
            contactRoots.add(roots.valueAt(i));
        }

        ArrayList<MergeContact> result = new ArrayList<MergeContact>();
        for (RootContact c : contactRoots) {
            result.add(c);
            result.addAll(c.contacts);
        }

        for (MergeContact c : result) {
            String prefix = (c instanceof RootContact) ? ":: " : "   :: ";
            Contact con = mapper.getContactById((int)c.id, false, false);
            Log.d("GRAPH_CONV", prefix + con.getDisplayName());
        }

        return result;
    }

}
