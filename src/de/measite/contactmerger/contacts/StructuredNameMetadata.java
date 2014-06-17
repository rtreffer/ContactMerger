package de.measite.contactmerger.contacts;

import android.provider.ContactsContract.CommonDataKinds.StructuredName;


/**
 * Photo Metadata is a contact photo, stored in the data column 15.
 */
public class StructuredNameMetadata extends Metadata {

    /**
     * The photo metadata mimetype.
     */
    public static final String MIMETYPE = StructuredName.MIMETYPE;

    /**
     * Create a new Photo Metadata instance.
     */
    public StructuredNameMetadata() {
        mimetype = MIMETYPE;
    }

    public String getDisplayName() {
        return getData(0);
    }

    public void setDisplayName(String name) {
        setData(0, name);
    }

    public String getGivenName() {
        return getData(1);
    }

    public void setGivenName(String name) {
        setData(1, name);
    }

    public String getFamilyName() {
        return getData(2);
    }

    public void setFamilyName(String name) {
        setData(2, name);
    }

    public String getPrefix() {
        return getData(3);
    }

    public void setPrefix(String name) {
        setData(3, name);
    }

    public String getMiddleName() {
        return getData(4);
    }

    public void setMiddleName(String name) {
        setData(4, name);
    }

    public String getSuffix() {
        return getData(5);
    }

    public void setSuffix(String name) {
        setData(5, name);
    }

    public String getPhoneticGivenName() {
        return getData(6);
    }

    public void setPhoneticGivenName(String name) {
        setData(6, name);
    }

    public String getPhoneticMiddleName() {
        return getData(8);
    }

    public void setPhoneticMiddleName(String name) {
        setData(8, name);
    }

    public String getPhoneticFamilyName() {
        return getData(9);
    }

    public void setPhoneticFamilyName(String name) {
        setData(9, name);
    }

    /**
     * Disallowed as the mimetype of a photo is fixed.
     * @param mimetype Ignored.
     */
    @Override
    public void setMimetype(String mimetype) {
        throw new UnsupportedOperationException("Mimetype of PhotoMetadata is " + MIMETYPE);
    }

}
