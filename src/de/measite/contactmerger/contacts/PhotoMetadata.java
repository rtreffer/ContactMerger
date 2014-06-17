package de.measite.contactmerger.contacts;

import android.provider.ContactsContract.CommonDataKinds.Photo;

/**
 * Photo Metadata is a contact photo, stored in the data column 15.
 */
public class PhotoMetadata extends Metadata {

    /**
     * The photo metadata mimetype.
     */
    public static final String MIMETYPE = Photo.MIMETYPE;

    /**
     * Create a new Photo Metadata instance.
     */
    public PhotoMetadata() {
        mimetype = MIMETYPE;
    }

    /**
     * Change the photo bytestream. This is equivilent to changing DATA15.
     * @param data The new image data content.
     */
    public void setPhoto(byte data[]) {
        setBlob(data);
    }

    /**
     * Retrieve the photo bytestream.
     * @return The photo bytes.
     */
    public byte[] getPhoto() {
        return getBlob();
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
