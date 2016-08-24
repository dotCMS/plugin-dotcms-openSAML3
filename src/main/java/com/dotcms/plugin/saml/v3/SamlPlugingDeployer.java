package com.dotcms.plugin.saml.v3;

import com.dotmarketing.filters.AutoLoginFilter;
import com.dotmarketing.plugin.PluginDeployer;
import com.dotmarketing.util.Config;

import javax.servlet.ServletContext;
import com.dotcms.filters.interceptor.FilterWebInterceptorProvider;
import com.dotcms.filters.interceptor.WebInterceptorDelegate;

/**
 * Saml Plugin Deployer
 * @author jsanca
 */
public class SamlPlugingDeployer implements PluginDeployer {

    protected boolean setup () {

        final ServletContext context = Config.CONTEXT;
        final FilterWebInterceptorProvider filterWebInterceptorProvider =
                FilterWebInterceptorProvider.getInstance(context);

        final WebInterceptorDelegate autoLoginDelegate =
                filterWebInterceptorProvider.getDelegate(AutoLoginFilter.class);

        autoLoginDelegate.addFirst(new SamlAutoLoginWebInterceptor());

        final WebInterceptorDelegate loginRequiredDelegate =
                filterWebInterceptorProvider.getDelegate(LoginRequiredFilter.class);

        loginRequiredDelegate.addFirst(new SamlLoginRequiredWebInterceptor());

        return true;
    }

    @Override
    public boolean deploy() {

        return this.setup();
    }

    @Override
    public boolean redeploy(final String version) {

        return this.setup();
    }
} // E:O:F:SamlPlugingDeployer.
