package org.sagebionetworks.snapshot.workers.writers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.audit.dao.ObjectRecordDAO;
import org.sagebionetworks.audit.utils.ObjectRecordBuilderUtils;
import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.kinesis.AwsKinesisFirehoseLogger;
import org.sagebionetworks.repo.manager.UserProfileManager;
import org.sagebionetworks.repo.model.GroupMembersDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.TeamDAO;
import org.sagebionetworks.repo.model.TeamMember;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.UserGroupHeader;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.audit.ObjectRecord;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.snapshot.workers.KinesisObjectSnapshotRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PrincipalObjectRecordWriter implements ObjectRecordWriter {
	static private Logger log = LogManager.getLogger(PrincipalObjectRecordWriter.class);
    private static final String TEAM_SNAPSHOT_STREAM = "teamSnapshots";
    private static final String TEAM_MEMBER_SNAPSHOT_STREAM = "teamMemberSnapshots";
    private static final String USER_PROFILE_SNAPSHOT_STREAM = "userProfileSnapshots";
    private static final String USER_GROUP_SNAPSHOT_STREAM = "userGroupSnapshots";
	private UserGroupDAO userGroupDAO;
	private UserProfileManager userProfileManager;
	private TeamDAO teamDAO;
	private GroupMembersDAO groupMembersDAO;
	private ObjectRecordDAO objectRecordDAO;
    private AwsKinesisFirehoseLogger firehoseLogger;
	@Autowired
	public PrincipalObjectRecordWriter(UserGroupDAO userGroupDAO, UserProfileManager userProfileManager, 
			TeamDAO teamDAO, GroupMembersDAO groupMembersDAO, ObjectRecordDAO objectRecordDAO,
                                       AwsKinesisFirehoseLogger firehoseLogger) {
		this.userGroupDAO = userGroupDAO;
		this.userProfileManager = userProfileManager;
		this.teamDAO = teamDAO;
		this.objectRecordDAO = objectRecordDAO;
		this.groupMembersDAO = groupMembersDAO;
        this.firehoseLogger = firehoseLogger;
	}

	@Override
	public void buildAndWriteRecords(ProgressCallback progressCallback, List<ChangeMessage> messages) throws IOException {
        List<ObjectRecord> groups = new LinkedList<ObjectRecord>();
        List<ObjectRecord> individuals = new LinkedList<ObjectRecord>();
		List<KinesisObjectSnapshotRecord<Team>>  kinesisTeamRecords = new ArrayList<>();
		List<KinesisObjectSnapshotRecord<UserProfile>>  kinesisUserProfileRecords = new ArrayList<>();
		List<KinesisObjectSnapshotRecord<UserGroup>>  kinesisUserGroups = new ArrayList<>();
		for (ChangeMessage message : messages) {
			if (message.getObjectType() != ObjectType.PRINCIPAL) {
				throw new IllegalArgumentException();
			}
			// skip delete messages
			if (message.getChangeType() == ChangeType.DELETE) {
				continue;
			}
			Long principalId = Long.parseLong(message.getObjectId());
			UserGroup userGroup = null;
			try {
				userGroup = userGroupDAO.get(principalId);
				ObjectRecord objectRecord = ObjectRecordBuilderUtils.buildObjectRecord(userGroup, message.getTimestamp().getTime());
				objectRecordDAO.saveBatch(Arrays.asList(objectRecord), objectRecord.getJsonClassName());
				kinesisUserGroups.add(KinesisObjectSnapshotRecord.map(message, userGroup));

				if(userGroup.getIsIndividual()){
					// User
					try {
						UserProfile profile = userProfileManager.getUserProfile(message.getObjectId());
						profile.setSummary(null);
						ObjectRecord upRecord = ObjectRecordBuilderUtils.buildObjectRecord(profile, message.getTimestamp().getTime());
						individuals.add(upRecord);
                        kinesisUserProfileRecords.add(KinesisObjectSnapshotRecord.map(message, profile));
					} catch (NotFoundException e) {
						log.warn("UserProfile not found: "+principalId);
					}
				} else {
					// Group
					captureAllMembers(message);
					try {
						Team team = teamDAO.get(message.getObjectId());
						ObjectRecord teamRecord = ObjectRecordBuilderUtils.buildObjectRecord(team, message.getTimestamp().getTime());
						groups.add(teamRecord);
                        kinesisTeamRecords.add(KinesisObjectSnapshotRecord.map(message, team));
					} catch (NotFoundException e) {
						log.warn("Team not found: "+principalId);
					}
				}

			} catch (NotFoundException e) {
				log.warn("Principal not found: "+principalId);
			}
		}
		if (!groups.isEmpty()) {
			objectRecordDAO.saveBatch(groups, groups.get(0).getJsonClassName());
		}
		if (!individuals.isEmpty()) {
			objectRecordDAO.saveBatch(individuals, individuals.get(0).getJsonClassName());
		}
        if (!kinesisTeamRecords.isEmpty()) {
            firehoseLogger.logBatch(TEAM_SNAPSHOT_STREAM, kinesisTeamRecords);
        }
        if (!kinesisUserProfileRecords.isEmpty()) {
            firehoseLogger.logBatch(USER_PROFILE_SNAPSHOT_STREAM, kinesisUserProfileRecords);
        }
        if (!kinesisUserGroups.isEmpty()) {
            firehoseLogger.logBatch(USER_GROUP_SNAPSHOT_STREAM, kinesisUserGroups);
        }
	}
	
	@Override
	public ObjectType getObjectType() {
		return ObjectType.PRINCIPAL;
	}

	/**
	 * Log all members that belongs to this group
	 * 
	 * @param message- the change message retrieved from queue
	 * @throws IOException 
	 */
	public void captureAllMembers(ChangeMessage message) throws IOException {
		String groupId= message.getObjectId();
		long timestamp= message.getTimestamp().getTime();
		List<UserGroup> members = groupMembersDAO.getMembers(groupId);
		Set<String> adminIds = teamDAO.getAdminTeamMemberIds(groupId).stream().collect(Collectors.toSet());
		List<ObjectRecord> records = new ArrayList<ObjectRecord>();
		List<KinesisObjectSnapshotRecord<TeamMember>> kinesisTeamMemberRecords = new ArrayList<>();
		for (UserGroup member : members) {
			TeamMember teamMember = new TeamMember();
			teamMember.setTeamId(groupId);
			UserGroupHeader ugh = new UserGroupHeader();
			ugh.setOwnerId(member.getId());
			teamMember.setMember(ugh);
			teamMember.setIsAdmin(adminIds.contains(member.getId()));
			records.add(ObjectRecordBuilderUtils.buildObjectRecord(teamMember, timestamp));
            kinesisTeamMemberRecords.add(KinesisObjectSnapshotRecord.map(message, teamMember));
		}
		if (records.size() > 0) {
			objectRecordDAO.saveBatch(records, records.get(0).getJsonClassName());
		}
        if (!kinesisTeamMemberRecords.isEmpty()) {
            firehoseLogger.logBatch(TEAM_MEMBER_SNAPSHOT_STREAM, kinesisTeamMemberRecords);
        }
	}
}
