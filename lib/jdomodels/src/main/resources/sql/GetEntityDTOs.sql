SELECT 
 N.ID,
 R.NUMBER,
 N.CURRENT_REV_NUM,
 N.CREATED_BY,
 N.CREATED_ON,
 N.ETAG, N.NAME,
 N.NODE_TYPE,
 N.PARENT_ID,
 getEntityBenefactorId(N.ID),
 getEntityProjectId(N.ID),
 R.DESCRIPTION,
 R.MODIFIED_BY,
 R.MODIFIED_ON,
 R.FILE_HANDLE_ID,
 R.USER_ANNOTATIONS,
 R.ENTITY_PROPERTY_ANNOTATIONS,
 DA.ANNOTATIONS,
 F.METADATA_TYPE,
 F.CONTENT_SIZE,
 F.CONTENT_MD5,
 F.BUCKET_NAME,
 F.KEY
FROM 
 JDONODE N JOIN JDOREVISION R ON (N.ID = R.OWNER_NODE_ID)
 LEFT JOIN FILES F ON (R.FILE_HANDLE_ID = F.ID)
 LEFT JOIN DERIVED_ANNOTATIONS DA ON (N.ID = DA.OBJECT_ID AND N.CURRENT_REV_NUM = R.NUMBER)
 WHERE N.ID IN(:NODE_IDS)
 ORDER BY N.ID ASC LIMIT :limit OFFSET :offset