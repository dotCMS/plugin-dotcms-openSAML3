package com.dotcms.plugin.saml.v3.init;

import com.dotcms.plugin.saml.v3.DotSamlConstants;
import com.dotcms.plugin.saml.v3.config.Configuration;
import com.dotcms.plugin.saml.v3.config.DefaultDotCMSConfiguration;
import com.dotcms.plugin.saml.v3.config.SiteConfigurationParser;
import com.dotcms.plugin.saml.v3.content.HostService;
import com.dotcms.plugin.saml.v3.content.SamlContentTypeUtil;
import com.dotcms.plugin.saml.v3.exception.DotSamlException;
import com.dotcms.plugin.saml.v3.hooks.SamlHostPostHook;
import com.dotcms.repackage.com.google.common.annotations.VisibleForTesting;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.Interceptor;
import com.dotmarketing.util.Logger;
import com.liferay.util.InstancePool;
import org.opensaml.core.config.InitializationException;
import org.opensaml.core.config.InitializationService;
import org.opensaml.xmlsec.config.JavaCryptoValidationInitializer;

import java.security.Provider;
import java.security.Security;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default initializer
 * Responsibilities:
 *
 * - Init the Java Crypto.
 * - Init Saml Services.
 * - Init Plugin Configuration and meta data.
 *
 *
 * @author jsanca
 */
public class DefaultInitializer implements Initializer {

    private final AtomicBoolean initDone = new AtomicBoolean(false);
    private final SamlContentTypeUtil samlContentTypeUtil;

    public DefaultInitializer(){

        this(new SamlContentTypeUtil());
    }

    @VisibleForTesting
    public DefaultInitializer(final SamlContentTypeUtil samlContentTypeUtil) {

        this.samlContentTypeUtil = samlContentTypeUtil;
    }

    @Override
    public void init(final Map<String, Object> context) {

        Logger.info(this, "About to create SAML field under Host Content Type");
        this.createSAMLFields();
        SamlHostPostHook postHook = new SamlHostPostHook();
        Interceptor interceptor = (Interceptor)APILocator.getContentletAPIntercepter();
        interceptor.delPostHookByClassName(postHook.getClass().getName());
        try {
            interceptor.addPostHook(postHook);
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            Logger.error(this, "Error adding SamlHostPostHook", e);
        }

        Logger.info(this, "Init java crypto");
        this.initJavaCrypto();

        for (Provider jceProvider : Security.getProviders()) {

            Logger.info(this, jceProvider.getInfo());
        }

        Logger.info(this, "Init Saml Services");
        this.initService();

        Logger.info(this, "Init Plugin Configuration");
        this.initConfiguration ();

        Logger.info(this, "Saml Init DONE");

        this.initDone.set(true);
    } // init.

    /**
     * 1. Get the Host Structure.
     * 2. Create a SAML field if the Structure doesn't have one.
     *
     * We need a SAML field(textare) under the Host structure in order
     * to have a place to configure SAML for each Site.
     */
    private void createSAMLFields() {

        this.samlContentTypeUtil.checkORCreateSAMLField();
    }// createSAMLFields.


    /**
     * Inits the app configuration.
     * The configuration By default is executed by {@link DefaultDotCMSConfiguration}
     * however you can override the implementation by your own implementation by implementing {@link Configuration}
     * and setting the classpath on the property {@link DotSamlConstants}.DOT_SAML_CONFIGURATION_CLASS_NAME
     * on the dotmarketing-config.properties
     */
    protected void initConfiguration() {

        final SiteCofigurationInitializerService siteCofigurationInitializerService =
                (SiteCofigurationInitializerService) InstancePool.get(SiteConfigurationParser.class.getName());

        siteCofigurationInitializerService.init(Collections.emptyMap());
    } // initConfiguration.



    /**
     * Inits the OpenSaml service.
     */
    protected void initService() {

        InstancePool.put(HostService.class.getName(),
                new HostService());

        InstancePool.put(SiteConfigurationParser.class.getName(),
                new SiteCofigurationInitializerService());

        try {

            Logger.info(this, "Initializing");
            InitializationService.initialize();
        } catch (InitializationException e) {

            Logger.error(this, e.getMessage(), e);
            throw new DotSamlException("Initialization failed");
        }
    } // initService.

    /**
     * Init Java Crypto stuff.
     */
    protected void initJavaCrypto() {

        final JavaCryptoValidationInitializer javaCryptoValidationInitializer
                = new JavaCryptoValidationInitializer();
        try {

            javaCryptoValidationInitializer.init();
        } catch (InitializationException e) {

            Logger.error(this, e.getMessage(), e);
        }
    } // initJavaCrypto.

    @Override
    public boolean isInitializationDone() {

        return this.initDone.get();
    } // isInitializationDone.

} // E:O:F:DefaultInitializer.
