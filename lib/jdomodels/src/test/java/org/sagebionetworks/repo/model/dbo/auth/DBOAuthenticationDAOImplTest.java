package org.sagebionetworks.repo.model.dbo.auth;



import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.StackConfigurationSingleton;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.auth.AuthenticationDAO;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.persistence.DBOAuthenticatedOn;
import org.sagebionetworks.repo.model.dbo.persistence.DBOCredential;
import org.sagebionetworks.repo.model.dbo.persistence.DBOTermsOfUseAgreement;
import org.sagebionetworks.repo.model.principal.BootstrapPrincipal;
import org.sagebionetworks.repo.model.principal.BootstrapUser;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.securitytools.PBKDF2Utils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;


@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class DBOAuthenticationDAOImplTest {
	
	@Autowired
	private AuthenticationDAO authDAO;
	
	@Autowired
	private UserGroupDAO userGroupDAO;
	
	@Autowired
	private DBOBasicDao basicDAO;
		
	private List<String> groupsToDelete;
	
	private Long userId;
	private DBOCredential credential;
	private DBOAuthenticatedOn authOn;
	private DBOTermsOfUseAgreement touAgreement;
	private static String userEtag;
	
	private static final Date VALIDATED_ON = new Date();

	
	@BeforeEach
	public void setUp() throws Exception {
		groupsToDelete = new ArrayList<String>();
		
		// Initialize a UserGroup
		UserGroup ug = new UserGroup();
		ug.setIsIndividual(true);
		userId = userGroupDAO.create(ug);
	
		groupsToDelete.add(userId.toString());
		userEtag = userGroupDAO.getEtagForUpdate(userId.toString());

		// Make a row of Credentials
		credential = new DBOCredential();
		credential.setPrincipalId(userId);
		credential.setPassHash("{PKCS5S2}1234567890abcdefghijklmnopqrstuvwxyz");
		credential.setSecretKey("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
		credential = basicDAO.createNew(credential);
		
		authOn = new DBOAuthenticatedOn();
		authOn.setPrincipalId(userId);
		authOn.setAuthenticatedOn(VALIDATED_ON);
		authOn.setEtag(UUID.randomUUID().toString());
		authOn = basicDAO.createNew(authOn);
		
		touAgreement = new DBOTermsOfUseAgreement();
		touAgreement.setPrincipalId(userId);
		touAgreement.setAgreesToTermsOfUse(true);
		touAgreement = basicDAO.createNew(touAgreement);
	}

	@AfterEach
	public void tearDown() throws Exception {
		for (String toDelete: groupsToDelete) {
			userGroupDAO.delete(toDelete);
		}
	}
	
	@Test
	public void testCheckUserCredentials() throws Exception {
		// Valid combination
		assertTrue(authDAO.checkUserCredentials(userId, credential.getPassHash()));
		
		// Invalid combinations
		assertFalse(authDAO.checkUserCredentials(userId, "Blargle"));

		assertFalse(authDAO.checkUserCredentials(-99, credential.getPassHash()));

		assertFalse(authDAO.checkUserCredentials(-100, "Blargle"));
	}
	
	@Test
	public void testGetWithoutToUAcceptance() throws Exception {
		touAgreement.setAgreesToTermsOfUse(false);
		basicDAO.update(touAgreement);
		
		DBOTermsOfUseAgreement tou = new DBOTermsOfUseAgreement();
		tou.setPrincipalId(credential.getPrincipalId());
		tou.setAgreesToTermsOfUse(Boolean.FALSE);
		basicDAO.createOrUpdate(tou);
		
	}
	
	@Test
	public void testChangePassword() throws Exception {
		// The original credentials should authenticate correctly
		assertTrue(authDAO.checkUserCredentials(userId, credential.getPassHash()));
		
		// Change the password and try to authenticate again
		authDAO.changePassword(credential.getPrincipalId(), "Bibbity Boppity BOO!");
		
		// This time it should fail
		assertFalse(authDAO.checkUserCredentials(userId, credential.getPassHash()));
	}
	
	@Test
	public void testSecretKey() throws Exception {
		Long userId = credential.getPrincipalId();
		
		// Getter should work
		assertEquals(credential.getSecretKey(), authDAO.getSecretKey(userId));
		
		// Setter should work
		authDAO.changeSecretKey(userId);
		assertFalse(credential.getSecretKey().equals(authDAO.getSecretKey(userId)));
		
		// Verify that the parent group's etag has changed
		String changedEtag = userGroupDAO.getEtagForUpdate(userId.toString());
		assertTrue(!userEtag.equals(changedEtag));
	}
	
	@Test
	public void testGetPasswordSalt() throws Exception {
		String passHash = PBKDF2Utils.hashPassword("password", null);
		byte[] salt = PBKDF2Utils.extractSalt(passHash);
		
		// Change the password to a valid one
		authDAO.changePassword(credential.getPrincipalId(), passHash);
		
		// Compare the salts
		byte[] passedSalt = authDAO.getPasswordSalt(userId);
		assertArrayEquals(salt, passedSalt);
	}
	
	@Test
	public void testGetPasswordSalt_InvalidUser() throws Exception {
		assertThrows(NotFoundException.class, ()->{
			authDAO.getPasswordSalt(-99);
		});
	}
	
	@Test
	public void testSetToU() throws Exception {
		Long userId = credential.getPrincipalId();
		
		// Reject the terms
		authDAO.setTermsOfUseAcceptance(userId, false);
		assertFalse(authDAO.hasUserAcceptedToU(userId));
		
		// Verify that the parent group's etag has changed
		String changedEtag = userGroupDAO.getEtagForUpdate("" + userId);
		assertTrue(!userEtag.equals(changedEtag));
		
		// Accept the terms
		authDAO.setTermsOfUseAcceptance(userId, true);
		assertTrue(authDAO.hasUserAcceptedToU(userId));
		
		// Verify that the parent group's etag has changed
		userEtag = changedEtag;
		changedEtag = userGroupDAO.getEtagForUpdate("" + userId);
		assertTrue(!userEtag.equals(changedEtag));
		
		// Pretend we haven't had a chance to see the terms yet
		authDAO.setTermsOfUseAcceptance(userId, null);
		assertFalse(authDAO.hasUserAcceptedToU(userId));
		
		// Verify that the parent group's etag has changed
		userEtag = changedEtag;
		changedEtag = userGroupDAO.getEtagForUpdate("" + userId);
		assertTrue(!userEtag.equals(changedEtag));
		
		// Accept the terms again
		authDAO.setTermsOfUseAcceptance(userId, true);
		assertTrue(authDAO.hasUserAcceptedToU(userId));
		
		// Verify that the parent group's etag has changed
		userEtag = changedEtag;
		changedEtag = userGroupDAO.getEtagForUpdate("" + userId);
		assertTrue(!userEtag.equals(changedEtag));
	}
	
	@Test
	public void testBootstrapCredentials() throws Exception {
		// Most bootstrapped users should have signed the terms
		List<BootstrapPrincipal> ugs = userGroupDAO.getBootstrapPrincipals();
		for (BootstrapPrincipal agg: ugs) {
			if (agg instanceof BootstrapUser 
					&& !AuthorizationUtils.isUserAnonymous(agg.getId())) {
				MapSqlParameterSource param = new MapSqlParameterSource();
				param.addValue("principalId", agg.getId());
				DBOCredential creds = basicDAO.getObjectByPrimaryKey(DBOCredential.class, param).get();
				assertTrue(touAgreement.getAgreesToTermsOfUse());
			}
		}
		
		// Migration admin should have a specific API key
		String secretKey = authDAO.getSecretKey(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		assertEquals(StackConfigurationSingleton.singleton().getMigrationAdminAPIKey(), secretKey);
	}
	
	@Test
	public void testGetSessionValidatedOn() {
		// if no validation date, return null
		assertNull(authDAO.getAuthenticatedOn(999999L));
		
		// check that 'userId's validation date is as expected
		Date validatedOn = authDAO.getAuthenticatedOn(userId);
		assertEquals(VALIDATED_ON.getTime(), validatedOn.getTime());
		
		
	}
	
	@Test
	public void testSetAuthenticatedOn() {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("principalId", userId);
		DBOAuthenticatedOn original = basicDAO.getObjectByPrimaryKey(DBOAuthenticatedOn.class, param).get();
		
		Date newAuthOn = new Date(original.getAuthenticatedOn().getTime()+10000L);
		
		//method under test
		authDAO.setAuthenticatedOn(userId, newAuthOn);
		
		DBOAuthenticatedOn updated = basicDAO.getObjectByPrimaryKey(DBOAuthenticatedOn.class, param).get();
		// check that date has been set
		assertEquals(newAuthOn, updated.getAuthenticatedOn());
		// check that etag has changed
		assertNotEquals(original.getEtag(), updated.getEtag());
	}
	
	@Test
	public void testSetGetTwoFactorAuthState() {
		
		assertFalse(authDAO.isTwoFactorAuthEnabled(userId));
		
		String userEtag = userGroupDAO.get(userId).getEtag();
		
		// Call under test
		authDAO.setTwoFactorAuthState(userId, true);
		
		assertTrue(authDAO.isTwoFactorAuthEnabled(userId));
		assertNotEquals(userEtag, userEtag = userGroupDAO.get(userId).getEtag());
		
		// Call under test
		authDAO.setTwoFactorAuthState(userId, false);
		
		assertFalse(authDAO.isTwoFactorAuthEnabled(userId));
		assertNotEquals(userEtag, userEtag = userGroupDAO.get(userId).getEtag());
	}

}
