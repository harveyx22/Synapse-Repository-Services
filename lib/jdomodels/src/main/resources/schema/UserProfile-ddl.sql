CREATE TABLE `JDOUSERPROFILE` (
  `OWNER_ID` bigint(20) NOT NULL,
  `ETAG` char(36) NOT NULL,
  `PROPERTIES` mediumblob,
  PRIMARY KEY (`OWNER_ID`),
  CONSTRAINT `USERGROUP_OWNER_FK` FOREIGN KEY (`OWNER_ID`) REFERENCES `JDOUSERGROUP` (`ID`) ON DELETE CASCADE
)