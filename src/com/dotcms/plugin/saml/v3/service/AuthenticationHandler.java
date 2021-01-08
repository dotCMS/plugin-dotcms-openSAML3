package com.dotcms.plugin.saml.v3.service;

import com.dotcms.plugin.saml.v3.config.IdpConfig;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Encapsulates the Authentication Handler, could be POST or Redirect (default)
 * @author jsanca
 */
public interface AuthenticationHandler {

    /**
     * Handles the authentication method
     * @param request    HttpServletRequest
     * @param response  {@link HttpServletResponse}
     * @param idpConfig {@link IdpConfig}
     */
    void handle(final HttpServletRequest request, final HttpServletResponse response,
                final IdpConfig idpConfig);
}