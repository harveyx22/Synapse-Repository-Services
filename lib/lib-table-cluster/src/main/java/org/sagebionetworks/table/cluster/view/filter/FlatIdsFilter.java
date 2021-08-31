package org.sagebionetworks.table.cluster.view.filter;

import java.util.Set;

import org.sagebionetworks.repo.model.table.MainType;
import org.sagebionetworks.repo.model.table.SubType;

/**
 * ViewFitler defined by a flat list of objectIds.
 *
 */
public class FlatIdsFilter extends AbstractViewFilter {
	
	protected final Set<Long> scope;

	public FlatIdsFilter(MainType mainType, Set<SubType> subTypes, Set<Long> scope) {
		this(mainType, subTypes, null, null, scope);
	}

	public FlatIdsFilter(MainType mainType, Set<SubType> subTypes, Set<Long> limitObjectIds, Set<String> excludeKeys,
			Set<Long> scope) {
		super(mainType, subTypes, limitObjectIds, excludeKeys);
		this.scope = scope;
		this.params.addValue("flatIds", scope);
	}

	@Override
	public boolean isEmpty() {
		return this.scope.isEmpty();
	}

	@Override
	public String getFilterSql() {
		return super.getFilterSql()+ " AND R.OBJECT_ID IN (:flatIds) AND R.OBJECT_VERSION = R.CURRENT_VERSION";
	}

	@Override
	public Builder newBuilder() {
		return new Builder(mainType, subTypes, limitObjectIds, excludeKeys, scope);
	}

	
	static class Builder extends AbstractBuilder {
		
		 Set<Long> scope;

		public Builder(MainType mainType, Set<SubType> subTypes, Set<Long> limitObjectIds,
				Set<String> excludeKeys,  Set<Long> scope) {
			super(mainType, subTypes, limitObjectIds, excludeKeys);
			this.scope = scope;
		}

		@Override
		public ViewFilter build() {
			return new FlatIdsFilter(mainType, subTypes, limitObjectIds, excludeKeys, scope);
		}
		
	}
}
