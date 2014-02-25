package org.apache.usergrid.management;


import java.util.Map;
import java.util.UUID;

import org.apache.usergrid.persistence.TypedEntity;


/**
 * Created by ApigeeCorporation on 1/31/14.
 */
//TODO: Documentation on this class.
public class ExportInfo extends TypedEntity {

    private String path;
    private Map<String, Object> properties;
    private String storage_provider;
    private Map<String, Object> storage_info;
    private String s3_accessId;
    private String s3_key;
    private String bucket_location;
    private UUID applicationId;

    public ExportInfo ( Map<String, Object> exportData) {
        path = (String) exportData.get("path");
        properties = (Map) exportData.get("properties");
        storage_provider = (String) properties.get ("storage_provider");
        storage_info = (Map) properties.get("storage_info");
        s3_accessId = (String) storage_info.get("s3_accessId");
        s3_key = (String) storage_info.get("s3_key");
        bucket_location = (String) storage_info.get("bucket_location");
    }


    public UUID getApplicationId() {
        return applicationId;
    }


    public String getPath () {
        return path;
    }

    //Wouldn't get exposed.
    public Map<String, Object> getProperties() {
        return properties;
    }

    public String getStorage_provider () {
        return storage_provider;
    }
    //TODO: write setter methods

    public Map<String, Object> getStorage_info () { return storage_info; }

    //TODO: is this a security concern? How would we get rid of the key once we're done with this value?
    public String getS3_key () { return s3_key; }

    public String getBucket_location () { return bucket_location; }

    public String getS3_accessId () { return s3_accessId; }

    public void setApplicationId (UUID appId) { applicationId = appId;}
}