package de.measite.contactmerger.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeSet;

import android.content.ContentProviderClient;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.util.SparseArray;
import de.measite.contactmerger.contacts.Contact;
import de.measite.contactmerger.contacts.ContactDataMapper;
import de.measite.contactmerger.contacts.RawContact;
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

            Contact left = mapper.getContactById(c1, true, false);
            Contact right = mapper.getContactById(c2, true, false);

            if (left == null) {
                continue;
            }
            if (right == null) {
                continue;
            }
            if (left.getId() == right.getId()) {
                continue;
            }

            // check if those 2 contacts should be kept separate
            boolean separate = false;
            StringBuilder sb = new StringBuilder();
            for (RawContact l : left.getRawContacts()) {
                if (sb.length() > 1) {
                    sb.append(",");
                }
                sb.append(l.getID());
            }
            String leftIn = sb.toString();
            sb.setLength(0);
            for (RawContact r : right.getRawContacts()) {
                if (sb.length() > 1) {
                    sb.append(",");
                }
                sb.append(r.getID());
            }
            String rightIn = sb.toString();
            try {
                Cursor c = provider.query(
                    ContactsContract.AggregationExceptions.CONTENT_URI,
                    new String[]{ContactsContract.AggregationExceptions.TYPE},
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID1 + " IN (" + leftIn + ") AND " +
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID2 + " IN (" + rightIn + ")",
                    null,null
                );
                if (c.moveToFirst()) while (!c.isAfterLast()) {
                    final int index = c.getColumnIndex(ContactsContract.AggregationExceptions.TYPE);
                    int type = c.getInt(index);
                    if (type == ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE) {
                        separate = true;
                        break;
                    }
                    c.moveToNext();
                }
                c.close();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            if (separate) {
                continue;
            }
            try {
                Cursor c = provider.query(
                        ContactsContract.AggregationExceptions.CONTENT_URI,
                        new String[]{ContactsContract.AggregationExceptions.TYPE},
                        ContactsContract.AggregationExceptions.RAW_CONTACT_ID1 + " IN (" + rightIn + ") AND " +
                                ContactsContract.AggregationExceptions.RAW_CONTACT_ID2 + " IN (" + leftIn + ")",
                        null,null
                );
                if (c.moveToFirst()) while (!c.isAfterLast()) {
                    final int index = c.getColumnIndex(ContactsContract.AggregationExceptions.TYPE);
                    int type = c.getInt(index);
                    if (type == ContactsContract.AggregationExceptions.TYPE_KEEP_SEPARATE) {
                        separate = true;
                        break;
                    }
                    c.moveToNext();
                }
                c.close();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            if (separate) {
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
                ArrayList<MergeContact> contacts =
                        new ArrayList<MergeContact>(root1.contacts.size());
                for (MergeContact m : root1.contacts) {
                    if (m.id != c1 && m.id != c2) {
                        contacts.add(m);
                    }
                }
                root1.contacts = contacts;
            }
            if (root == null) {
                Contact ref = null;
		if (right == null || right.getDisplayName() == null) {
		    ref = left;
		} else
                if (left == null || left.getDisplayName() == null) {
		    ref = right;
		} else
                if (right.getDisplayName().length() > left.getDisplayName().length()) {
                    ref = right;
                } else {
                    ref = left;
                }
		if (ref == null) continue;
                root = new RootContact();
                root.sortName = ref.getSortKeyPrimary();
                root.id = ref.getId();
                roots.put((int)left.getId(), root);
                roots.put((int)right.getId(), root);
            }

            boolean addc1 = c1 != root.id;
            boolean addc2 = c2 != root.id;
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

        return result;
    }

}
