CREATE TABLE IF NOT EXISTS `TABLE_TRX_TO_VERSION` (
  `TRX_ID` bigint(20) NOT NULL,
  `VERSION` bigint(20) NOT NULL,
  PRIMARY KEY (`TRX_ID`, `VERSION`),
  CONSTRAINT `TRX_LINK_ID_FK` FOREIGN KEY (`TRX_ID`) REFERENCES `TABLE_TRANSACTION` (`TRX_ID`) ON DELETE CASCADE
)
