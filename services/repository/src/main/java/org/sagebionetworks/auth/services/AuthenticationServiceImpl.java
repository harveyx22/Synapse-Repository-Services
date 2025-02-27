package org.sagebionetworks.auth.services;

import org.sagebionetworks.repo.manager.AuthenticationManager;
import org.sagebionetworks.repo.manager.MessageManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.authentication.PersonalAccessTokenManager;
import org.sagebionetworks.repo.manager.authentication.TwoFactorAuthManager;
import org.sagebionetworks.repo.manager.oauth.AliasAndType;
import org.sagebionetworks.repo.manager.oauth.OAuthManager;
import org.sagebionetworks.repo.manager.oauth.OpenIDConnectManager;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AccessToken;
import org.sagebionetworks.repo.model.auth.AccessTokenGenerationRequest;
import org.sagebionetworks.repo.model.auth.AccessTokenGenerationResponse;
import org.sagebionetworks.repo.model.auth.AccessTokenRecord;
import org.sagebionetworks.repo.model.auth.AccessTokenRecordList;
import org.sagebionetworks.repo.model.auth.AuthenticatedOn;
import org.sagebionetworks.repo.model.auth.ChangePasswordInterface;
import org.sagebionetworks.repo.model.auth.LoginRequest;
import org.sagebionetworks.repo.model.auth.LoginResponse;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.auth.PasswordResetSignedToken;
import org.sagebionetworks.repo.model.auth.TotpSecret;
import org.sagebionetworks.repo.model.auth.TotpSecretActivationRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthLoginRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthRecoveryCodes;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthStatus;
import org.sagebionetworks.repo.model.oauth.OAuthAccountCreationRequest;
import org.sagebionetworks.repo.model.oauth.OAuthProvider;
import org.sagebionetworks.repo.model.oauth.OAuthUrlRequest;
import org.sagebionetworks.repo.model.oauth.OAuthUrlResponse;
import org.sagebionetworks.repo.model.oauth.OAuthValidationRequest;
import org.sagebionetworks.repo.model.oauth.ProvidedUserInfo;
import org.sagebionetworks.repo.model.principal.AliasType;
import org.sagebionetworks.repo.model.principal.PrincipalAlias;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class AuthenticationServiceImpl implements AuthenticationService {


	@Autowired
	private UserManager userManager;
	
	@Autowired
	private AuthenticationManager authManager;
	
	@Autowired
	private OAuthManager oauthManager;
	
	@Autowired
	private OpenIDConnectManager oidcManager;
	
	@Autowired
	private MessageManager messageManager;

	@Autowired
	private PersonalAccessTokenManager personalAccessTokenManager;
	
	@Autowired
	private TwoFactorAuthManager twoFaManager;
	
	@WriteTransaction
	@Override
	public void changePassword(ChangePasswordInterface request) throws NotFoundException {
		final long userId = authManager.changePassword(request);
		messageManager.sendPasswordChangeConfirmationEmail(userId);
	}

	@Override
	@WriteTransaction
	public void signTermsOfUse(AccessToken accessToken) throws NotFoundException {
		ValidateArgument.required(accessToken, "Access token");
		ValidateArgument.required(accessToken.getAccessToken(), "Access token contents");
		
		Long principalId = Long.parseLong(oidcManager.validateAccessToken(accessToken.getAccessToken()));
		
		// Save the state of acceptance
		authManager.setTermsOfUseAcceptance(principalId, true);
	}
	
	@Override
	public String getSecretKey(Long principalId) throws NotFoundException {
		return authManager.getSecretKey(principalId);
	}
	
	@Override
	@WriteTransaction
	public void deleteSecretKey(Long principalId) throws NotFoundException {
		authManager.changeSecretKey(principalId);
	}
	
	@Override
	public boolean hasUserAcceptedTermsOfUse(Long userId) throws NotFoundException {
		return authManager.hasUserAcceptedTermsOfUse(userId);
	}

	@Override
	public void sendPasswordResetEmail(String passwordResetUrlPrefix, String usernameOrEmail) {
		try {
			PrincipalAlias principalAlias = userManager.lookupUserByUsernameOrEmail(usernameOrEmail);
			PasswordResetSignedToken passwordRestToken = authManager.createPasswordResetToken(principalAlias.getPrincipalId());
			messageManager.sendNewPasswordResetEmail(passwordResetUrlPrefix, passwordRestToken, principalAlias);
		} catch (NotFoundException e) {
			// should not indicate that a email/user could not be found
		}
	}

	@Override
	public OAuthUrlResponse getOAuthAuthenticationUrl(OAuthUrlRequest request) {
		String url = oauthManager.getAuthorizationUrl(request.getProvider(), request.getRedirectUrl(), request.getState());
		OAuthUrlResponse response = new OAuthUrlResponse();
		response.setAuthorizationUrl(url);
		return response;
	}

	@Override
	public LoginResponse validateOAuthAuthenticationCodeAndLogin(
			OAuthValidationRequest request, String tokenIssuer) throws NotFoundException {
		// Use the authentication code to lookup the user's information.
		ProvidedUserInfo providedInfo = oauthManager.validateUserWithProvider(
				request.getProvider(), request.getAuthenticationCode(), request.getRedirectUrl());
		
		if (providedInfo.getUsersVerifiedEmail() == null){
			throw new IllegalArgumentException("OAuthProvider: "+request.getProvider().name()+" did not provide a user email");
		}
		
		if (providedInfo.getSubject() == null) {
			throw new IllegalArgumentException("OAuthProvider: "+request.getProvider().name()+" did not provide the user subject");
		}
		
		// We first lookup for a potential subject already bound for the given provider
		Long principalId = userManager.lookupUserIdByOIDCSubject(request.getProvider(), providedInfo.getSubject()).orElseGet(() -> {
			// If not found, for backward compatibility we also lookup the user by the provider verified email
			PrincipalAlias alias = userManager.lookupUserByUsernameOrEmail(providedInfo.getUsersVerifiedEmail());
			// Finally, we also migrate the user to the oauth provider subject (See https://sagebionetworks.jira.com/browse/PLFM-7302)
			userManager.bindUserToOIDCSubject(alias.getPrincipalId(), request.getProvider(), providedInfo.getSubject());
			return alias.getPrincipalId();
		});
		
		// Return the user's access token
		return authManager.loginWithNoPasswordCheck(principalId, tokenIssuer);
	}
	
	@WriteTransaction
	public LoginResponse createAccountViaOauth(OAuthAccountCreationRequest request, String tokenIssuer) {
		// Use the authentication code to lookup the user's information.
		ProvidedUserInfo providedInfo = oauthManager.validateUserWithProvider(
				request.getProvider(), request.getAuthenticationCode(), request.getRedirectUrl());
		
		if (providedInfo.getUsersVerifiedEmail() == null){
			throw new IllegalArgumentException("OAuthProvider: "+request.getProvider().name()+" did not provide a user email");
		}
		
		if (providedInfo.getSubject() == null) {
			throw new IllegalArgumentException("OAuthProvider: "+request.getProvider().name()+" did not provide the user subject");
		}
		
		// create account with the returned user info.
		NewUser newUser = new NewUser();
		
		newUser.setEmail(providedInfo.getUsersVerifiedEmail());
		newUser.setFirstName(providedInfo.getFirstName());
		newUser.setLastName(providedInfo.getLastName());
		newUser.setUserName(request.getUserName());
		newUser.setOauthProvider(request.getProvider());
		newUser.setSubject(providedInfo.getSubject());
		
		long newPrincipalId = userManager.createUser(newUser);

		return authManager.loginWithNoPasswordCheck(newPrincipalId, tokenIssuer);

	}
	
	@Override
	public PrincipalAlias bindExternalID(Long userId, OAuthValidationRequest validationRequest) {
		if (AuthorizationUtils.isUserAnonymous(userId)) throw new UnauthorizedException("User ID is required.");
		AliasAndType providersUserId = oauthManager.retrieveProvidersId(
				validationRequest.getProvider(), 
				validationRequest.getAuthenticationCode(),
				validationRequest.getRedirectUrl());
		// now bind the ID to the user account
		return userManager.bindAlias(providersUserId.getAlias(), providersUserId.getType(), userId);
	}
	
	@Override
	public void unbindExternalID(Long userId, OAuthProvider provider, String aliasName) {
		if (AuthorizationUtils.isUserAnonymous(userId)) throw new UnauthorizedException("User ID is required.");
		AliasType aliasType = oauthManager.getAliasTypeForProvider(provider);
		userManager.unbindAlias(aliasName, aliasType, userId);
	}

	@Override
	public LoginResponse login(LoginRequest request, String tokenIssuer) {
		return authManager.login(request, tokenIssuer);
	}

	@Override
	public AuthenticatedOn getAuthenticatedOn(long userId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return authManager.getAuthenticatedOn(userInfo);
	}

	@Override
	public PrincipalAlias lookupUserForAuthentication(String alias) {
		return userManager.lookupUserByUsernameOrEmail(alias);
	}

	@Override
	public AccessTokenGenerationResponse createPersonalAccessToken(Long userId, String accessToken, AccessTokenGenerationRequest request, String oauthEndpoint) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return personalAccessTokenManager.issueToken(userInfo, accessToken, request, oauthEndpoint);
	}

	@Override
	public AccessTokenRecordList getPersonalAccessTokenRecords(Long userId, String nextPageToken) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return personalAccessTokenManager.getTokenRecords(userInfo, nextPageToken);
	}

	@Override
	public AccessTokenRecord getPersonalAccessTokenRecord(Long userId, Long tokenId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		return personalAccessTokenManager.getTokenRecord(userInfo, tokenId.toString());
	}

	@Override
	public void revokePersonalAccessToken(Long userId, Long tokenId) {
		UserInfo userInfo = userManager.getUserInfo(userId);
		personalAccessTokenManager.revokeToken(userInfo, tokenId.toString());
	}

	@Override
	public TotpSecret enroll2Fa(Long userId) {
		UserInfo user = userManager.getUserInfo(userId);
		return twoFaManager.init2Fa(user);
	}

	@Override
	public TwoFactorAuthStatus enable2Fa(Long userId, TotpSecretActivationRequest request) {
		UserInfo user = userManager.getUserInfo(userId);
		twoFaManager.enable2Fa(user, request);
		return twoFaManager.get2FaStatus(user);
	}

	@Override
	public void disable2Fa(Long userId) {
		UserInfo user = userManager.getUserInfo(userId);
		twoFaManager.disable2Fa(user);
	}

	@Override
	public TwoFactorAuthStatus get2FaStatus(Long userId) {
		UserInfo user = userManager.getUserInfo(userId);
		return twoFaManager.get2FaStatus(user);
	}
	
	@Override
	public TwoFactorAuthRecoveryCodes generate2faRecoveryCodes(Long userId) {
		UserInfo user = userManager.getUserInfo(userId);
		return twoFaManager.generate2FaRecoveryCodes(user);
	}
	
	@Override
	public LoginResponse loginWith2Fa(TwoFactorAuthLoginRequest request, String issuer) {
		return authManager.loginWith2Fa(request, issuer);
	}

}
