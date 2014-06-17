package de.measite.contactmerger.contacts;

public class Contact {

    protected RawContact rawContacts[] = null;

    protected long _id = -1l;

    protected String displayName;

    protected String displayNameAlternative;

    protected String displayNamePrimary;

    protected String displayNameSource;

    protected String phoneticNameStyle;

    protected String phoneticName;

    protected long photoFileId;

    protected long photoId;

    protected String photoThumbnailUri;

    protected String photoUri;

    protected String sortKeyAlternative;

    protected String sortKeyPrimary;

    public RawContact[] getRawContacts() {
        return rawContacts;
    }

    public void setRawContacts(RawContact[] rawContacts) {
        this.rawContacts = rawContacts;
    }

    public long getId() {
        return _id;
    }

    public void setId(long _id) {
        this._id = _id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayNameAlternative() {
        return displayNameAlternative;
    }

    public void setDisplayNameAlternative(String displayNameAlternative) {
        this.displayNameAlternative = displayNameAlternative;
    }

    public String getDisplayNamePrimary() {
        return displayNamePrimary;
    }

    public void setDisplayNamePrimary(String displayNamePrimary) {
        this.displayNamePrimary = displayNamePrimary;
    }

    public String getDisplayNameSource() {
        return displayNameSource;
    }

    public void setDisplayNameSource(String displayNameSource) {
        this.displayNameSource = displayNameSource;
    }

    public String getPhoneticNameStyle() {
        return phoneticNameStyle;
    }

    public void setPhoneticNameStyle(String phoneticNameStyle) {
        this.phoneticNameStyle = phoneticNameStyle;
    }

    public String getPhoneticName() {
        return phoneticName;
    }

    public void setPhoneticName(String phoneticName) {
        this.phoneticName = phoneticName;
    }

    public long getPhotoFileId() {
        return photoFileId;
    }

    public void setPhotoFileId(long photoFileId) {
        this.photoFileId = photoFileId;
    }

    public long getPhotoId() {
        return photoId;
    }

    public void setPhotoId(long photoId) {
        this.photoId = photoId;
    }

    public String getPhotoThumbnailUri() {
        return photoThumbnailUri;
    }

    public void setPhotoThumbnailUri(String photoThumbnailUri) {
        this.photoThumbnailUri = photoThumbnailUri;
    }

    public String getPhotoUri() {
        return photoUri;
    }

    public void setPhotoUri(String photoUri) {
        this.photoUri = photoUri;
    }

    public String getSortKeyAlternative() {
        return sortKeyAlternative;
    }

    public void setSortKeyAlternative(String sortKeyAlternative) {
        this.sortKeyAlternative = sortKeyAlternative;
    }

    public String getSortKeyPrimary() {
        return sortKeyPrimary;
    }

    public void setSortKeyPrimary(String sortKeyPrimary) {
        this.sortKeyPrimary = sortKeyPrimary;
    }

    
}
