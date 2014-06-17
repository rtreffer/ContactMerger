package de.measite.contactmerger.ui.model;

public class MergeContact implements Comparable<MergeContact> {

    public RootContact root;

    public long id;

    @Override
    public int compareTo(MergeContact another) {
        return root.sortName.compareToIgnoreCase(another.root.sortName);
    }

}
