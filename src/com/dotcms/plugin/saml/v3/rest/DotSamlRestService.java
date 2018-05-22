package com.dotcms.plugin.saml.v3.rest;

import static com.dotcms.plugin.saml.v3.key.DotSamlConstants.DEFAULT_LOGIN_PATH;
import static com.dotcms.plugin.saml.v3.key.DotSamlConstants.SAML_USER_ID;
import static com.dotcms.plugin.saml.v3.util.SamlUtils.SAML_NAME_ID;
import static com.dotcms.plugin.saml.v3.util.SamlUtils.SAML_SESSION_INDEX;
import static com.dotcms.plugin.saml.v3.util.SamlUtils.getSessionIndex;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.opensaml.saml.saml2.core.Assertion;

import com.dotcms.plugin.saml.v3.config.IdpConfig;
import com.dotcms.plugin.saml.v3.config.IdpConfigHelper;
import com.dotcms.plugin.saml.v3.config.OptionalPropertiesHelper;
import com.dotcms.plugin.saml.v3.exception.DotSamlException;
import com.dotcms.plugin.saml.v3.filter.SamlFilter;
import com.dotcms.plugin.saml.v3.key.DotSamlConstants;
import com.dotcms.repackage.javax.ws.rs.GET;
import com.dotcms.repackage.javax.ws.rs.POST;
import com.dotcms.repackage.javax.ws.rs.Path;
import com.dotcms.repackage.javax.ws.rs.PathParam;
import com.dotcms.repackage.javax.ws.rs.Produces;
import com.dotcms.repackage.javax.ws.rs.core.Context;
import com.dotcms.repackage.javax.ws.rs.core.MediaType;
import com.dotcms.repackage.org.glassfish.jersey.server.JSONP;
import com.dotcms.rest.annotation.NoCache;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.WebKeys;
import com.dotmarketing.util.json.JSONException;
import com.liferay.portal.model.User;

@Path("/dotsaml")
public class DotSamlRestService implements Serializable {
	private static final long serialVersionUID = 6901877501532737335L;
	private final IdpConfigHelper idpConfigHelper;
	public static final List<String> dotsamlPathSegments = new ArrayList<String>() {
		{
			add("login");
			add("logout");
			add("metadata");
		}
	};

	public DotSamlRestService() {
		this.idpConfigHelper = IdpConfigHelper.getInstance();
	}

	@POST
	@Path("/login/{idpConfigId}")
	@NoCache
	// Login configuration by id
	public void login(@PathParam("idpConfigId") final String idpConfigId,
			@Context final HttpServletRequest httpServletRequest,
			@Context final HttpServletResponse httpServletResponse) {
		User user = null;
		Assertion assertion;

		try {
			// Get the idpConfig
			final IdpConfig idpConfig = idpConfigHelper.findIdpConfig(idpConfigId);
			if (idpConfig == null || !idpConfig.isEnabled()) {
				String message = "No idpConfig for idpConfigId: " + idpConfigId + ". At "
						+ httpServletRequest.getRequestURI();
				Logger.debug(this, message);
				throw new DotSamlException(message);
			}

			SamlFilter samlFilter = new SamlFilter();
			samlFilter.doRequestLoginSecurityLog(httpServletRequest, idpConfig);

			HttpSession session = httpServletRequest.getSession();
			if (null == session) {
				throw new DotSamlException("No session created.");
			}

			// Extracts data from the assertion - if it can't process a
			// DotSamlException is thrown
			assertion = samlFilter.samlAuthenticationService.resolveAssertion(httpServletRequest, httpServletResponse,
					idpConfig);
			Logger.debug(this, "Resolved assertion: " + assertion);

			// Creates the user object and adds a user if it doesn't already
			// exist
			user = samlFilter.samlAuthenticationService.resolveUser(assertion, idpConfig);
			if (null == user) {
				throw new DotSamlException("User cannot be extracted from Assertion!");
			}
			Logger.debug(this, "Resolved user: " + user);

			final String samlSessionIndex = getSessionIndex(assertion);
			if (null != samlSessionIndex) {

				Logger.debug(this, "SAMLSessionIndex: " + samlSessionIndex);
				// Session Attributes used to build logout request
				session.setAttribute(idpConfig.getId() + SAML_SESSION_INDEX, samlSessionIndex);
				session.setAttribute(idpConfig.getId() + SAML_NAME_ID, assertion.getSubject().getNameID());
				Logger.debug(this, "Already set the session index with key:" + (idpConfig.getId() + SAML_SESSION_INDEX)
						+ " and value" + session.getAttribute(idpConfig.getId() + SAML_SESSION_INDEX));
				Logger.debug(this, "Already set the name id with key:" + (idpConfig.getId() + SAML_NAME_ID)
						+ " and value" + session.getAttribute(idpConfig.getId() + SAML_NAME_ID));
			}

			// Add session based user ID to be used on the redirect.
			session.setAttribute(idpConfig.getId() + SAML_USER_ID, user.getUserId());

			String loginPath = (String) session.getAttribute(WebKeys.REDIRECT_AFTER_LOGIN);
			if (null == loginPath) {
				// At this stage we cannot determine whether this was a front
				// end or back end request since we cannot determine
				// original request.
				//
				// REDIRECT_AFTER_LOGIN should have already been set in relay
				// request to IdP. 'autoLogin' will check the ORIGINAL_REQUEST
				// session attribute.
				loginPath = DEFAULT_LOGIN_PATH;
			} else {
				session.removeAttribute(WebKeys.REDIRECT_AFTER_LOGIN);
			}

			httpServletResponse.sendRedirect(loginPath);

		} catch (DotSamlException dotSamlException) {

			Logger.error(this, dotSamlException.getMessage(), dotSamlException);

		} catch (Exception exception) {

			// this is an unknown error, so we report as a 500.
			Logger.error(this, "Error getting posting idp", exception);
		}

	}

	@POST
	@Path("/logout/{idpConfigId}")
	@NoCache
	// Login configuration by id
	public void logout(@PathParam("idpConfigId") final String idpConfigId,
			@Context final HttpServletRequest httpServletRequest,
			@Context final HttpServletResponse httpServletResponse) {

		try {
			// Get the idpConfig
			final IdpConfig idpConfig = idpConfigHelper.findIdpConfig(idpConfigId);
			if (idpConfig == null || !idpConfig.isEnabled()) {
				String message = "No idpConfig for idpConfigId: " + idpConfigId + ". At "
						+ httpServletRequest.getRequestURI();
				Logger.debug(this, message);
				throw new DotSamlException(message);
			}

			String logoutPath = OptionalPropertiesHelper.getOptionString(idpConfig,
					DotSamlConstants.DOT_SAML_LOGOUT_SERVICE_ENDPOINT_URL,
					buildBaseUrlFromRequest(httpServletRequest) + "/");

			httpServletResponse.sendRedirect(logoutPath);

		} catch (DotSamlException dotSamlException) {

			Logger.error(this, dotSamlException.getMessage(), dotSamlException);

		} catch (Exception exception) {

			// this is an unknown error, so we report as a 500.
			Logger.error(this, "Error getting posting idp", exception);
		}
	}

	@GET
	@Path("/metadata/{idpConfigId}")
	@JSONP
	@NoCache
	@Produces({ MediaType.APPLICATION_JSON, "application/javascript" })
	// Gets metadata configuration by id
	public void metadata(@PathParam("idpConfigId") final String idpConfigId,
			@Context final HttpServletRequest httpServletRequest,
			@Context final HttpServletResponse httpServletResponse) {
		try {
			final IdpConfig idpConfig = idpConfigHelper.findIdpConfig(idpConfigId);

			// If idpConfig is null, means this site does not need SAML
			// processing
			if (idpConfig != null) {
				Logger.debug(this, "Processing saml login request for idpConfig id: " + idpConfigId);
				SamlFilter samlFilter = new SamlFilter();
				samlFilter.printMetaData(httpServletRequest, httpServletResponse, idpConfig);

			} else {
				String message = "No idpConfig for idpConfigId: " + idpConfigId + ". At "
						+ httpServletRequest.getRequestURI();
				Logger.debug(this, message);
				throw new DotSamlException(message);
			}

		} catch (DotSamlException dotSamlException) {

			Logger.error(this, dotSamlException.getMessage(), dotSamlException);

		} catch (DotDataException dotDataException) {

			Logger.error(this, "Idp not found (" + dotDataException.getMessage() + ")", dotDataException);

		} catch (IOException ioException) {

			Logger.error(this, "Idp is not valid (" + ioException.getMessage() + ")", ioException);

		} catch (JSONException jsonException) {

			Logger.error(this, "Error handling json (" + jsonException.getMessage() + ")", jsonException);

		} catch (Exception exception) {

			// this is an unknown error, so we report as a 500.
			Logger.error(this, "Error getting posting idp", exception);
		}
	}

	/*
	 * Builds the base url from the initiating Servlet Request.
	 */
	private String buildBaseUrlFromRequest(HttpServletRequest httpServletRequest) {
		String uri = httpServletRequest.getScheme() + "://" + httpServletRequest.getServerName() + ":"
				+ httpServletRequest.getServerPort();

		return uri;
	}
}