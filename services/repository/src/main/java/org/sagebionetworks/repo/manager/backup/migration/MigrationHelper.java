package org.sagebionetworks.repo.manager.backup.migration;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.List;

import java.lang.Long;
import java.util.*;

import org.sagebionetworks.repo.model.Annotations;
import org.sagebionetworks.repo.model.registry.MigrationSpecData.FieldMigrationSpecData;

// TODO: Add type transformation
public class MigrationHelper {
	public static void migrateBucket(Annotations srcAnnots, Annotations dstAnnots, FieldMigrationSpecData fmsd) {
		if (null != srcAnnots.getAllValues(fmsd.getSrcFieldName())) {
			migrateType(srcAnnots, dstAnnots, fmsd);
			Object srcData = srcAnnots.deleteAnnotation(fmsd.getSrcFieldName());
			dstAnnots.addAnnotation(fmsd.getDestFieldName(), srcData);
		}
		
		return;
	}
	
	public static void deleteFromAnnotations(Annotations srcAnnots, FieldMigrationSpecData fmsd) {
		if (null != srcAnnots.getAllValues(fmsd.getSrcFieldName())) {
			Object srcData = srcAnnots.deleteAnnotation(fmsd.getSrcFieldName());
		}
		
		return;
	}
	
	// TODO: Move type migration to separate migration step
	private static void migrateType(Annotations srcAnnots, Annotations dstAnnots, FieldMigrationSpecData fmsd) {
		// Only support string to int for now
		if (fmsd.getSrcType().equals("string") && fmsd.getDestType().equals("integer")) {
			try {
				List<String> ls = srcAnnots.getStringAnnotations().remove(fmsd.getSrcFieldName());
				List<Long> ll = new ArrayList<Long>();
				Long l;
				for (String s: ls) {
					l = Long.valueOf(s);
					ll.add(l);
				}
				srcAnnots.getLongAnnotations().put(fmsd.getSrcFieldName(), ll);
			} catch (NumberFormatException e) {
				// Should log something here
			} finally {
				return;
			}
		}
	}
}
