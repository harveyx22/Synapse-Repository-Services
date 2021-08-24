CREATE TABLE IF NOT EXISTS ANNOTATION_REPLICATION(
	OBJECT_TYPE ENUM ('SUBMISSION','ENTITY') NOT NULL,
	OBJECT_ID BIGINT NOT NULL,
	OBJECT_VERSION BIGINT NOT NULL,
	ANNO_KEY VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
	ANNO_TYPE ENUM('STRING','LONG','DOUBLE','DATE','BOOLEAN') NOT NULL,
	MAX_STRING_LENGTH BIGINT NOT NULL,
	LIST_LENGTH BIGINT NOT NULL,
	STRING_VALUE VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
	LONG_VALUE BIGINT DEFAULT NULL,
	DOUBLE_VALUE DOUBLE DEFAULT NULL,
	DOUBLE_ABSTRACT ENUM('NaN','Infinity','-Infinity') DEFAULT NULL,
	BOOLEAN_VALUE BOOLEAN DEFAULT NULL,
	STRING_LIST_VALUE JSON,
	LONG_LIST_VALUE JSON,
	BOOLEAN_LIST_VALUE JSON,
	PRIMARY KEY(OBJECT_ID,OBJECT_VERSION,OBJECT_TYPE,ANNO_KEY,ANNO_TYPE),
	INDEX `OBJECT_ID_OBJECT_TYPE_IDX` (OBJECT_ID,OBJECT_VERSION,OBJECT_TYPE),
	INDEX `STRING_VALUE_IDX`(STRING_VALUE),
	CONSTRAINT `OBJECT_ID_OBJECT_TYPE_FK` FOREIGN KEY (OBJECT_ID,OBJECT_VERSION,OBJECT_TYPE) REFERENCES OBJECT_REPLICATION (OBJECT_ID,OBJECT_VERSION,OBJECT_TYPE) ON DELETE CASCADE
)