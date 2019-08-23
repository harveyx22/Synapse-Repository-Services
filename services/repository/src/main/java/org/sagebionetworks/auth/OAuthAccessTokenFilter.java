package org.sagebionetworks.auth;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.sagebionetworks.authutil.ModParamHttpServletRequest;
import org.sagebionetworks.repo.manager.oauth.OIDCTokenUtil;
import org.sagebionetworks.repo.model.AuthorizationConstants;

public class OAuthAccessTokenFilter implements Filter {
	
	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {

		HttpServletRequest httpRequest = (HttpServletRequest)request;

		String bearerToken = HttpAuthUtil.getBearerToken(httpRequest);
		
		System.out.println("In OAuthAccessTokenFilter.  bearerToken: "+bearerToken); // TODO remove

		Map<String, String[]> modParams = new HashMap<String, String[]>(httpRequest.getParameterMap());
		// strip out access token request param so that the sender can't 'sneak it past us'
		modParams.remove(AuthorizationConstants.OAUTH_VERIFIED_ACCESS_TOKEN);

		boolean verified=false;
		if (bearerToken!=null) {
			verified = OIDCTokenUtil.validateSignedJWT(bearerToken);
			System.out.println("In OAuthAccessTokenFilter.  verified="+verified); // TODO remove
		}
		
		if (verified) {
			modParams.put(AuthorizationConstants.OAUTH_VERIFIED_ACCESS_TOKEN, new String[] {bearerToken});
		}
		
		HttpServletRequest modRqst = new ModParamHttpServletRequest(httpRequest, modParams);
		chain.doFilter(modRqst, response);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// Nothing to do
	}

	@Override
	public void destroy() {
		// Nothing to do
	}

}
