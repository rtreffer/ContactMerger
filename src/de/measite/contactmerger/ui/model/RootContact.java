package de.measite.contactmerger.ui.model;

import java.util.ArrayList;

public class RootContact extends MergeContact {

    public String sortName = "";

    public ArrayList<MergeContact> contacts = new ArrayList<MergeContact>(2);

    public RootContact() {
        super();
        root = this;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (id ^ (id >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RootContact other = (RootContact) obj;
        if (id != other.id)
            return false;
        return true;
    }

}
