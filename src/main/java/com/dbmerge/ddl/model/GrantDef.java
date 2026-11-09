package com.dbmerge.ddl.model;

/** An object privilege granted on one of the schema's objects (ALL_TAB_PRIVS). */
public record GrantDef(String objectName, String grantee, String privilege, boolean grantable) {

    /** Map key: one privilege for one grantee on one object. */
    public String key() {
        return objectName + ":" + privilege + ":" + grantee;
    }

    public String summary() {
        return privilege + " ON " + objectName + " TO " + grantee + (grantable ? " WITH GRANT OPTION" : "");
    }
}
