package org.sagebionetworks.table.cluster.columntranslation;

import java.util.Arrays;
import java.util.Optional;

import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.TableConstants;

/**
 * ColumnTranslationReference for row metadata columns. For these, the
 * userQueryColumnName and translatedColumnName are the same
 */
public enum RowMetadataColumnTranslationReference implements ColumnTranslationReference {
	ROW_ID(TableConstants.ROW_ID, ColumnType.INTEGER), ROW_VERSION(TableConstants.ROW_VERSION, ColumnType.INTEGER),
	ROW_ETAG(TableConstants.ROW_ETAG, ColumnType.STRING),
	ROW_BENEFACTOR(TableConstants.ROW_BENEFACTOR, ColumnType.INTEGER);

	// the translated name and queried name are the same
	private final String columnName;
	private final ColumnType columnType;

	RowMetadataColumnTranslationReference(final String columnName, final ColumnType columnType) {
		this.columnName = columnName.toUpperCase();
		this.columnType = columnType;
	}

	@Override
	public ColumnType getColumnType() {
		return columnType;
	}

	@Override
	public String getUserQueryColumnName() {
		return columnName;
	}

	@Override
	public String getTranslatedColumnName() {
		return columnName;
	}

	/**
	 * Attempt to match the given right-hand-side with one of the columns of this enumeration.
	 * @param rhs
	 * @return
	 */
	public static Optional<ColumnTranslationReference> lookupColumnReference(String rhs) {
		Optional<RowMetadataColumnTranslationReference> result = Arrays
				.stream(RowMetadataColumnTranslationReference.values()).filter(r -> rhs.equalsIgnoreCase(r.columnName))
				.findFirst();
		if(result.isPresent()) {
			return Optional.of((ColumnTranslationReference)result.get());
		}else {
			return Optional.empty();
		}
	}
}
