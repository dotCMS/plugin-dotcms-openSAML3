package com.dotcms.plugin.saml.v3;

import com.dotcms.plugin.saml.v3.config.Configuration;
import com.dotcms.plugin.saml.v3.exception.AttributesNotFoundException;
import com.dotcms.plugin.saml.v3.exception.DotSamlException;
import com.dotcms.plugin.saml.v3.handler.AssertionResolverHandler;
import com.dotcms.plugin.saml.v3.handler.AssertionResolverHandlerFactory;
import com.dotcms.repackage.com.google.common.annotations.VisibleForTesting;
import com.dotcms.repackage.org.apache.commons.lang.StringUtils;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.DotStateException;
import com.dotmarketing.business.NoSuchUserException;
import com.dotmarketing.business.Role;
import com.dotmarketing.business.RoleAPI;
import com.dotmarketing.business.UserAPI;
import com.dotmarketing.cms.factories.PublicEncryptionFactory;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.RoleNameException;
import com.dotmarketing.util.ActivityLogger;
import com.dotmarketing.util.AdminLogger;
import com.dotmarketing.util.DateUtil;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.RegEX;
import com.dotmarketing.util.UUIDGenerator;
import com.liferay.portal.language.LanguageUtil;
import com.liferay.portal.model.User;
import com.liferay.util.InstancePool;

import net.shibboleth.utilities.java.support.component.ComponentInitializationException;

import org.opensaml.core.xml.XMLObject;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.messaging.encoder.MessageEncodingException;
import org.opensaml.saml.common.messaging.context.SAMLEndpointContext;
import org.opensaml.saml.common.messaging.context.SAMLPeerEntityContext;
import org.opensaml.saml.saml2.binding.encoding.impl.HTTPRedirectDeflateEncoder;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.xmlsec.SignatureSigningParameters;
import org.opensaml.xmlsec.context.SecurityParametersContext;
import org.opensaml.xmlsec.signature.support.SignatureConstants;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.dotcms.plugin.saml.v3.DotSamlConstants.*;
import static com.dotcms.plugin.saml.v3.SamlUtils.buildAuthnRequest;
import static com.dotcms.plugin.saml.v3.SamlUtils.getCredential;
import static com.dotcms.plugin.saml.v3.SamlUtils.getIdentityProviderDestinationEndpoint;
import static com.dotcms.plugin.saml.v3.SamlUtils.toXMLObjectString;
import static com.dotmarketing.util.UtilMethods.isSet;

/**
 * Authentication with Open SAML
 * @author jsanca
 */
public class OpenSamlAuthenticationServiceImpl implements SamlAuthenticationService {


    private static final String NULL = "null";
    private final UserAPI userAPI;
    private final RoleAPI roleAPI;
    private final AssertionResolverHandlerFactory assertionResolverHandlerFactory;

    public OpenSamlAuthenticationServiceImpl() {

        this(APILocator.getUserAPI(), APILocator.getRoleAPI(), new AssertionResolverHandlerFactory());
    }

    @VisibleForTesting
    protected OpenSamlAuthenticationServiceImpl(final UserAPI userAPI,
                                                final RoleAPI roleAPI,
                                                final AssertionResolverHandlerFactory assertionResolverHandlerFactory) {

        this.userAPI    = userAPI;
        this.roleAPI    = roleAPI;
        this.assertionResolverHandlerFactory =
                assertionResolverHandlerFactory;
    }

    /**
     * Authentication with Open SAML 3 is basically a redirect to the IDP to show the login page to the user.
     * @param request  {@link HttpServletRequest}
     * @param response {@link HttpServletResponse}
     */
    @Override
    public void authentication(final HttpServletRequest request,
                               final HttpServletResponse response,
                               final String siteName) {

        final SiteConfigurationResolver resolver      = (SiteConfigurationResolver)InstancePool.get(SiteConfigurationResolver.class.getName());
        final Configuration             configuration = resolver.resolveConfiguration(request);
        final MessageContext            context       = new MessageContext(); // main context
        final AuthnRequest              authnRequest  = buildAuthnRequest(request, configuration);

        context.setMessage(authnRequest);

        final SAMLPeerEntityContext peerEntityContext = // peer entity (Idp to SP and viceversa)
                context.getSubcontext(SAMLPeerEntityContext.class, true);
        final SAMLEndpointContext endpointContext = // info about the endpoint of the peer entity
                peerEntityContext.getSubcontext(SAMLEndpointContext.class, true);

        endpointContext.setEndpoint(
                getIdentityProviderDestinationEndpoint(configuration));

        this.setSignatureSigningParams(context, configuration);
        this.doRedirect(context, response, authnRequest);
    } // authentication.

    /**
     * When the authentication is performed and redirected to SO (DotCMS) you can call this method.
     * If the request contains a parameter called AMLart, will try to get the {@link org.opensaml.saml.saml2.core.Assertion}
     * with the user information via SOAP.
     *
     * - If the user exists, will just return the instance of it.
     *
     * - If the user does not exists on DotCMS will create a new one
     *
     * - If the existing user is active will also populate the roles.
     *
     * Note: if the parameter "SAMLart" does not exists, will return null.
     * @param request  {@link HttpServletRequest}
     * @param response {@link HttpServletResponse}
     * @return User
     */
    @Override
    public User getUser(final HttpServletRequest request,
                        final HttpServletResponse response,
                        final String siteName) {

        User user = null;
        final Assertion assertion;
        final SiteConfigurationResolver resolver      = (SiteConfigurationResolver)InstancePool.get(SiteConfigurationResolver.class.getName());
        final Configuration             configuration = resolver.resolveConfiguration(request);

        if (this.isValidSamlRequest (request, response, siteName)) {

            assertion = this.resolveAssertion(request, response, siteName);

            Logger.info (this, "Resolved assertion: " + assertion);

            user      = this.resolveUser(assertion, configuration);

            Logger.info (this, "Resolved user: " + user);
        }

        return user;
    } // getUser.

    private boolean isValidRole (final String role, final String [] rolePatterns) {

        boolean isValidRole = false;

        if (null != rolePatterns) {

            for (String rolePattern : rolePatterns) {

                Logger.debug(this, "Is Valid Role, role: " + role +
                                ", pattern: " + rolePattern);
                isValidRole |= this.match(role, rolePattern);
            }
        } else {

            isValidRole = true; // if not pattern, role is valid.
        }

        return isValidRole;
    } // isValidRole.

    private boolean match (final String role, final String rolePattern) {

        String uftRole = null;

        try {

            uftRole = URLDecoder.decode(role, "UTF-8");
        } catch (UnsupportedEncodingException e) {

            uftRole = role;
        }

        return RegEX.contains(uftRole, rolePattern);
    } // match.


    // resolve the attributes from the assertion resolved from the OpenSaml artifact resolver via
    // post soap message.
    private AttributesBean resolveAttributes (final Assertion assertion, final Configuration configuration) throws AttributesNotFoundException {

        final String emailField       = configuration.getStringProperty
                (DOT_SAML_EMAIL_ATTRIBUTE, "mail");
        final String firstNameField   = configuration.getStringProperty
                (DOT_SAML_FIRSTNAME_ATTRIBUTE, "givenName");
        final String lastNameField    = configuration.getStringProperty
                (DOT_SAML_LASTNAME_ATTRIBUTE, "sn");
        final String rolesField       = configuration.getStringProperty
                (DOT_SAML_ROLES_ATTRIBUTE, "authorizations");

        final AttributesBean.Builder attrBuilder = new AttributesBean.Builder();

        validateAttributes(assertion);

        attrBuilder.nameID(assertion.getSubject().getNameID());

        assertion.getAttributeStatements().get(0).getAttributes().forEach(attribute -> {

            if (attribute.getName().equals(emailField)) {

                attrBuilder.email
                        (attribute.getAttributeValues().get(0).getDOM().getFirstChild().getNodeValue());
            } else if (attribute.getName().equals(lastNameField)) {

                attrBuilder.lastName
                        (attribute.getAttributeValues().get(0).getDOM().getFirstChild().getNodeValue());
            } else if(attribute.getName().equals(firstNameField)){

                attrBuilder.firstName
                        (attribute.getAttributeValues().get(0).getDOM().getFirstChild().getNodeValue());
            }else if (attribute.getName().equals(rolesField)) {

                attrBuilder.addRoles(true).roles(attribute);
            }
        });

        return attrBuilder.build();
    } // resolveAttributes.

    private void validateAttributes(Assertion assertion) throws AttributesNotFoundException {
        if (assertion == null
            || assertion.getAttributeStatements() == null
            || assertion.getAttributeStatements().isEmpty()
            || assertion.getSubject() == null
            || assertion.getSubject().getNameID() == null
            || assertion.getSubject().getNameID().getValue().isEmpty()) {

            throw new AttributesNotFoundException("No attributes found");
        }

    }

    // Gets the attributes from the Assertion, based on the attributes
    // see if the user exists return it from the dotCMS records, if does not exist then, tries to create it.
    // the existing or created user, will be updated the roles if they present on the assertion.
    private User resolveUser(final Assertion assertion, final Configuration configuration) {

        User systemUser  = null;
        User user        = null;
        AttributesBean attributesBean = null;

        try {

            attributesBean = this.resolveAttributes(assertion, configuration);

            Logger.debug(this,
                "Validating user - " + attributesBean);

            systemUser = this.userAPI.getSystemUser();

            user = this.userAPI.loadUserById(attributesBean.getNameID().getValue(), systemUser, false);
        } catch (AttributesNotFoundException e){
            Logger.error(this, e.getMessage());
            return null;
        } catch (NoSuchUserException e) {
            Logger.info(this, "No matching user, creating");
            user = null;
        } catch (Exception e) {
            Logger.error(this, "Unknown exception", e);
            user = null;
        }

        if (null == user) { // if user does not exists, create a new one.

            user = this.createNewUser(systemUser, attributesBean);
        }

        if (user.isActive()) {

            this.addRoles(user, attributesBean, configuration);
        } else {

            Logger.info(this, "The user " + user.getEmailAddress() +
                            " is not active");
        }

        return user;
    } // resolveUser.

    private void addRoles(final User user,
                          final AttributesBean attributesBean, final Configuration configuration) {

        String role;

        try {

            // remove previous roles
            Logger.debug(this, "Removing user previous roles");
            this.roleAPI.removeAllRolesFromUser(user);

            if (attributesBean.isAddRoles() &&
                null != attributesBean.getRoles() &&
                null != attributesBean.getRoles().getAttributeValues() &&
                attributesBean.getRoles().getAttributeValues().size() > 0) {

                final String removeRolePrefix = configuration.getStringProperty
                    (DOT_SAML_REMOVE_ROLES_PREFIX, StringUtils.EMPTY);
                final String [] rolePatterns   = configuration.getStringArray
                    (DOTCMS_SAML_INCLUDE_ROLES_PATTERN, null);

                Logger.debug(this, "Role Patterns: " + this.toString(rolePatterns) +
                    ", remove role prefix: " + removeRolePrefix);

                //add roles
                for (XMLObject roleObject : attributesBean.getRoles().getAttributeValues()) {

                    if (null != rolePatterns && rolePatterns.length > 0) {

                        role = roleObject.getDOM().getFirstChild().getNodeValue();
                        if (!this.isValidRole(role, rolePatterns)) {
                            // when there are role filters and the current roles is not
                            // a valid role, we have to filter it.

                            Logger.info(this, "Skipping the role: " + role);
                            continue;
                        }
                    }

                    this.addRole(user, removeRolePrefix, roleObject);
                }
            }
            //Add SAML User role
            addRole(user, configuration.getStringProperty(DOTCMS_SAML_USER_ROLE, "SAML User"), true, true);

            //Add DOTCMS_SAML_OPTIONAL_USER_ROLE
            if (configuration.getStringProperty(DOTCMS_SAML_OPTIONAL_USER_ROLE, null) != null) {
                addRole(user, configuration.getStringProperty(DOTCMS_SAML_OPTIONAL_USER_ROLE, null), false, false);
            }

        } catch (DotDataException e) {

            Logger.error(this, "Error creating user:" + e.getMessage(), e);
            throw new DotSamlException(e.getMessage());
        }
    } // addRoles.

    private String toString(String[] rolePatterns) {

        return null == rolePatterns? NULL : Arrays.asList(rolePatterns).toString();
    }

    private void addRole(final User user, final String roleKey, final boolean createRole, final boolean isSystem) throws DotDataException{

        Role role = this.roleAPI.loadRoleByKey(roleKey);

        //create the role, in case it does not exist
        if (role == null && createRole){
            role = createNewRole(roleKey, isSystem);
        }

        if(null != role && !this.roleAPI.doesUserHaveRole(user, role)) {

            this.roleAPI.addRoleToUser(role, user);
            Logger.debug(this, "Added role: " + role.getName() +
                " to user:" + user.getEmailAddress());
        }
    } // addRole.

    private void addRole(final User user, final String removeRolePrefix,
                         final XMLObject roleObject) throws DotDataException {

        //remove role prefix
        final String roleKey = (isSet(removeRolePrefix))?
                roleObject.getDOM().getFirstChild().getNodeValue()
                        .replaceFirst(removeRolePrefix, StringUtils.EMPTY):
                roleObject.getDOM().getFirstChild().getNodeValue();

        addRole(user, roleKey, false, false);
    } // addRole.

    private Role createNewRole(String roleKey, boolean isSystem) throws DotDataException {
        Role role = new Role();
        role.setName(roleKey);
        role.setRoleKey(roleKey);
        role.setEditUsers(true);
        role.setEditPermissions(false);
        role.setEditLayouts(false);
        role.setDescription("");
        role.setId(UUIDGenerator.generateUuid());

        //Setting SYSTEM role as a parent
        role.setSystem(isSystem);
        Role parentRole = roleAPI.loadRoleByKey(Role.SYSTEM);
        role.setParent(parentRole.getId());

        String date = DateUtil.getCurrentDate();

        ActivityLogger.logInfo(getClass(), "Adding Role", "Date: " + date + "; " + "Role:" + roleKey);
        AdminLogger.log(getClass(), "Adding Role", "Date: " + date + "; " + "Role:" + roleKey);

        try {
            role = roleAPI.save(role, role.getId());
        } catch (DotDataException | DotStateException e) {
            ActivityLogger.logInfo(getClass(), "Error Adding Role", "Date: " + date + ";  " + "Role:" + roleKey);
            AdminLogger.log(getClass(), "Error Adding Role", "Date: " + date + ";  " + "Role:" + roleKey);
            throw e;
        }

        return role;
    }

    private User createNewUser(final User systemUser,
                               final AttributesBean attributesBean) {

        User user = null;
        final String userId;

        try {

            user   = this.userAPI.createUser(attributesBean.getNameID().getValue(), attributesBean.getEmail());

            user.setFirstName(attributesBean.getFirstName());
            user.setLastName (attributesBean.getLastName());
            user.setActive(true);

            user.setCreateDate(new Date());
            user.setPassword(PublicEncryptionFactory.digestString
                    (UUIDGenerator.generateUuid() + "/" + UUIDGenerator.generateUuid()));
            user.setPasswordEncrypted(true);

            this.userAPI.save(user, systemUser, false);
            Logger.debug(this, "new user created. email: " + attributesBean.getEmail());
        } catch (Exception e) {

            Logger.error(this, "Error creating user:" + e.getMessage(), e);
            throw new DotSamlException(e.getMessage());
        }

        return user;
    } // createNewUser.

    @Override
    public Assertion resolveAssertion(final HttpServletRequest request,
                        final HttpServletResponse response, final String siteName) {

        final AssertionResolverHandler assertionResolverHandler =
                this.assertionResolverHandlerFactory.getAssertionResolverForSite(siteName);

        return assertionResolverHandler.resolveAssertion(request, response, siteName);
    } // getAssertion.



    // if the SAML_ART_PARAM_KEY parameter is in the request, it is a valid SAML request
    @Override
    public boolean isValidSamlRequest(final HttpServletRequest request, final HttpServletResponse response, final String siteName) {

        final AssertionResolverHandler assertionResolverHandler =
                this.assertionResolverHandlerFactory.getAssertionResolverForSite(siteName);

        return assertionResolverHandler.isValidSamlRequest(request, response, siteName);
    } // isValidSamlRequest.



    // this makes the redirect to the IdP
    private void doRedirect (final MessageContext context,
                             final HttpServletResponse response,
                             final AuthnRequest authnRequest) {

        final HTTPRedirectDeflateEncoder encoder;

        try {

            encoder =
                    new HTTPRedirectDeflateEncoder();

            encoder.setMessageContext(context);
            encoder.setHttpServletResponse(response);

            encoder.initialize();

            Logger.info(this, "AuthnRequest: " + toXMLObjectString(authnRequest));
            Logger.info(this, "Redirecting to IDP");

            encoder.encode();
        } catch (ComponentInitializationException | MessageEncodingException e) {

            Logger.error(this, e.getMessage(), e);
            throw new DotSamlException(e.getMessage(), e);
        }
    } // doRedirect.

    private void setSignatureSigningParams(final MessageContext context, final Configuration configuration) {

        final SignatureSigningParameters signatureSigningParameters =
                new SignatureSigningParameters();

        signatureSigningParameters.setSigningCredential
                (getCredential(configuration));
        signatureSigningParameters.setSignatureAlgorithm
                (SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);

        context.getSubcontext(SecurityParametersContext.class, true)
                .setSignatureSigningParameters(signatureSigningParameters);
    } // setSignatureSigningParams.

} // E:O:F:OpenSamlAuthenticationServiceImpl.
