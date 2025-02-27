package org.sagebionetworks.repo.manager.replication;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Logger;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.table.TableIndexConnectionFactory;
import org.sagebionetworks.repo.manager.table.TableIndexManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.metadata.MetadataIndexProvider;
import org.sagebionetworks.repo.manager.table.metadata.MetadataIndexProviderFactory;
import org.sagebionetworks.repo.manager.table.metadata.ObjectDataProvider;
import org.sagebionetworks.repo.manager.table.metadata.ObjectDataProviderFactory;
import org.sagebionetworks.repo.model.IdAndChecksum;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.table.ObjectDataDTO;
import org.sagebionetworks.repo.model.table.ReplicationType;
import org.sagebionetworks.repo.model.table.SubType;
import org.sagebionetworks.repo.model.table.ViewScopeType;
import org.sagebionetworks.table.cluster.TableIndexDAO;
import org.sagebionetworks.table.cluster.view.filter.HierarchicaFilter;
import org.sagebionetworks.table.cluster.view.filter.IdAndVersionFilter;
import org.sagebionetworks.table.cluster.view.filter.ViewFilter;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;

import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;

@Service
public class ReplicationManagerImpl implements ReplicationManager {
	
	private final Logger log;

	public static final int MAX_ANNOTATION_CHARS = 500;
	
	public static final int MAX_MESSAGE_PAGE_SIZE = 1000;
	
	final private ObjectDataProviderFactory objectDataProviderFactory;
	final private MetadataIndexProviderFactory indexProviderFactory;

	final private TableManagerSupport tableManagerSupport;

	final private ReplicationMessageManager replicationMessageManager;
	
	final private TableIndexConnectionFactory indexConnectionFactory;
	
	final private Random random;

	@Autowired
	public ReplicationManagerImpl(
			ObjectDataProviderFactory objectDataProviderFactory, 
			TableManagerSupport tableManagerSupport,
			ReplicationMessageManager replicationMessageManager, 
			TableIndexConnectionFactory indexConnectionFactory,
			MetadataIndexProviderFactory indexProviderFactory, LoggerProvider logProvider,
			Random random) {
		this.objectDataProviderFactory = objectDataProviderFactory;
		this.tableManagerSupport = tableManagerSupport;
		this.replicationMessageManager = replicationMessageManager;
		this.indexConnectionFactory = indexConnectionFactory;
		this.indexProviderFactory = indexProviderFactory;
		this.log = logProvider.getLogger(ReplicationManagerImpl.class.getName());
		this.random = random;
	}

	/**
	 * Replicate the data for the provided entities.
	 * 
	 * @param progressCallback
	 * @param messages
	 */
	@Override
	public void replicate(List<ChangeMessage> messages) throws RecoverableMessageException {

		Map<ReplicationType, ReplicationDataGroup> data = groupByObjectType(messages);
		
		for (ReplicationDataGroup group : data.values()) {
			
			updateReplicationTables(group);
		}

	}

	/**
	 * Update the replication tables within a single transaction that removes rows to be deleted
	 * and creates or updates rows from the provided group.
	 * 
	 * @param replicationType
	 * @param toDelete
	 * @param objectData
	 */
	void updateReplicationTables(ReplicationDataGroup group) {
		TableIndexManager indexManager = indexConnectionFactory.connectToFirstIndex();
		
		indexManager.deleteObjectData(group.getObjectType(), group.getToDeleteIds());
		
		ObjectDataProvider provider = objectDataProviderFactory.getObjectDataProvider(group.getObjectType());
		Iterator<ObjectDataDTO> objectData = provider.getObjectData(group.getCreateOrUpdateIds(),
				MAX_ANNOTATION_CHARS);
		
		indexManager.updateObjectReplication(group.getObjectType(), objectData);
	}

	/**
	 * Replicate a single entity.
	 * 
	 */
	@Override
	public void replicate(ReplicationType replicationType, String objectId) {
		ValidateArgument.required(replicationType, "objectType");
		ValidateArgument.required(objectId, "objectId");
		
		ReplicationDataGroup group = new ReplicationDataGroup(replicationType);
		group.addForCreateOrUpdate(KeyFactory.stringToKey(objectId));
		 
		updateReplicationTables(group);
	}

	Map<ReplicationType, ReplicationDataGroup> groupByObjectType(List<ChangeMessage> messages) {
		Map<ReplicationType, ReplicationDataGroup> data = new HashMap<>();

		for (ChangeMessage message : messages) {
			// Skip messages that do not map to a view object type
			ReplicationType.matchType(message.getObjectType()).ifPresent(r->{
				ReplicationDataGroup group = data.computeIfAbsent(r, ReplicationDataGroup::new);

				Long id = KeyFactory.stringToKey(message.getObjectId());

				if (ChangeType.DELETE.equals(message.getChangeType())) {
					group.addForDelete(id);
				} else {
					group.addForCreateOrUpdate(id);
				}
			});
		}

		return data;
	}


	/**
	 * Replicate the given
	 * 
	 * @param indexDao
	 * @param entityDTOs DTO to be created/updated
	 * @param ids        All of the ids to be created/updated/deleted.
	 */
	void replicateInIndex(TableIndexDAO indexDao, ReplicationType objectType, List<ObjectDataDTO> entityDTOs,
			List<Long> ids) {
		indexDao.executeInWriteTransaction((TransactionStatus status) -> {
			indexDao.deleteObjectData(objectType, ids);
			indexDao.addObjectData(objectType, entityDTOs);
			return null;
		});
	}
	

	/**
	 * Will find any deltas between the 'truth' and 'replication'. Deltas are found
	 * by comparing the IDs and checksums of each object in the view in both the the
	 * 'truth' and 'replication' tables. For each delta found, a ChangeMessage will
	 * be pushed to the replication worker queue. Each batch of change messages will
	 * ultimately result in a call to {@link #replicate(List)}.
	 * 
	 * @param idAndVersion
	 * @param salt
	 * @param pageSize
	 */
	@Override
	public void reconcile(IdAndVersion idAndVersion, ObjectType type) {
		ValidateArgument.required(idAndVersion, "idAndVersion");
		ValidateArgument.required(type, "type");

		ReplicationType replicationType = getReplicationType(idAndVersion, type);
		TableIndexManager indexManager = indexConnectionFactory.connectToFirstIndex();
		
		if (!indexManager.isViewSynchronizeLockExpired(replicationType, idAndVersion)) {
			log.info(String.format("Synchronize lock for view: '%s' has not expired.  Will not synchronize.",
					idAndVersion.toString()));
			return;
		}

		ViewFilter filter = getFilter(idAndVersion, type);
		
		// Can this view be represented by sub-views?
		Optional<List<ChangeMessage>> subViews = filter.getSubViews();
		if(subViews.isPresent()) {
			/*
			 * Since this view can be represented as the union of multiple sub-views,
			 * we will push each sub-view back to queue to be processed individually.
			 * This allows us to process large views as many small concurrent blocks. 
			 */
			pushSubviewsBackToQueue(idAndVersion, subViews.get());
		}else {
			/*
			 *This view cannot be represented as sub-views, so the entire view must be processed. 
			 */
			reconcileView(idAndVersion, filter);
		}
		indexManager.resetViewSynchronizeLock(replicationType, idAndVersion);
		log.info(String.format("Finished reconcile for view: '%s'.", idAndVersion.toString()));
	}
	
	void pushSubviewsBackToQueue(IdAndVersion idAndVersion, List<ChangeMessage> toPush) {
		log.info(String.format("Pushing %d sub-view messages back to the reconciliation queue for view: '%s'.",
				toPush.size(), idAndVersion.toString()));
		replicationMessageManager.pushChangeMessagesToReconciliationQueue(toPush);
	}
	
	void reconcileView(IdAndVersion idAndVersion, ViewFilter filter) {
		Iterator<ChangeMessage> it = createReconcileIterator(filter);
		Iterators.partition(it, MAX_MESSAGE_PAGE_SIZE).forEachRemaining(page -> {
			log.info(String.format("Found %d objects out-of-synch between truth and replication for view: '%s'.",
					page.size(), idAndVersion.toString()));
			replicationMessageManager.pushChangeMessagesToReplicationQueue(page);
		});
	}
	
	/**
	 * 
	 * @param idAndVersion
	 * @param type
	 * @return
	 */
	ViewFilter getFilter(IdAndVersion idAndVersion, ObjectType type) {
		switch (type) {
		case ENTITY_VIEW:
			ViewScopeType viewScopeType = tableManagerSupport.getViewScopeType(idAndVersion);
			MetadataIndexProvider metadataProvider = indexProviderFactory
					.getMetadataIndexProvider(viewScopeType.getObjectType());
			return metadataProvider.getViewFilter(idAndVersion.getId());
		case ENTITY_CONTAINER:
			return new HierarchicaFilter(ReplicationType.ENTITY,
					Arrays.stream(SubType.values()).collect(Collectors.toSet()), Sets.newHashSet(idAndVersion.getId()));
		default:
			throw new IllegalStateException("Unknown type: " + type);
		}
	}

	/**
	 *
	 * @param idAndVersion
	 * @param type
	 * @return
	 */
	ReplicationType getReplicationType(IdAndVersion idAndVersion, ObjectType type) {
		switch (type) {
			case ENTITY_VIEW:
				return tableManagerSupport.getViewScopeType(idAndVersion).getObjectType().getMainType();
			case ENTITY_CONTAINER:
				return ReplicationType.ENTITY;
			default:
				throw new IllegalStateException("Unknown type: " + type);
		}
	}

	/**
	 * Create a stream of 'truth' IdAndChecksum from the provided filter.
	 * 
	 * @param filter
	 * @param salt
	 * @return
	 */
	Iterator<IdAndChecksum> createTruthStream(Long salt, ViewFilter filter) {
		ValidateArgument.required(salt, "salt");
		ValidateArgument.required(filter, "filter");
		ObjectDataProvider provider = objectDataProviderFactory.getObjectDataProvider(filter.getReplicationType());
		if (filter instanceof HierarchicaFilter) {
			HierarchicaFilter hierarchy = (HierarchicaFilter) filter;
			return provider.streamOverIdsAndChecksumsForChildren(salt, hierarchy.getParentIds(), filter.getSubTypes());
		} else if (filter instanceof IdAndVersionFilter) {
			IdAndVersionFilter flat = (IdAndVersionFilter) filter;
			return provider.streamOverIdsAndChecksumsForObjects(salt, flat.getObjectIds());
		} else {
			throw new IllegalStateException("Unknown filter types: " + filter.getClass().getName());
		}
	}
	
	/**
	 * Abstraction of the ReconcileIterator.
	 * @param objectType
	 * @param truth
	 * @param replication
	 * @return
	 */
	Iterator<ChangeMessage> createReconcileIterator(ViewFilter filter) {
		ValidateArgument.required(filter, "filter");
		long salt = random.nextLong();
		Iterator<IdAndChecksum> truthStream = createTruthStream(salt, filter);
		TableIndexManager indexManager = indexConnectionFactory.connectToFirstIndex();
		Iterator<IdAndChecksum> replicationStream = indexManager.streamOverIdsAndChecksums(salt, filter);
		return new ReconcileIterator(filter.getReplicationType().getObjectType(), truthStream, replicationStream);
	}
	
	@Override
	public boolean isReplicationSynchronizedForView(ObjectType viewObjectType, IdAndVersion viewId) {
		ViewFilter filter = getFilter(viewId, viewObjectType);
		Iterator<ChangeMessage> it = createReconcileIterator(filter);
		return !it.hasNext();
	}

}
