package org.apache.openejb.arquillian.openejb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Properties;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import org.apache.openejb.AppContext;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.OpenEJBRuntimeException;
import org.apache.openejb.OpenEjbContainer;
import org.apache.openejb.assembler.classic.AppInfo;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.openejb.config.AppModule;
import org.apache.openejb.config.ConfigurationFactory;
import org.apache.openejb.config.DeploymentFilterable;
import org.apache.openejb.core.LocalInitialContext;
import org.apache.openejb.core.LocalInitialContextFactory;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.spi.ContainerSystem;
import org.apache.webbeans.web.lifecycle.test.MockHttpSession;
import org.apache.webbeans.web.lifecycle.test.MockServletContext;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.context.annotation.ContainerScoped;
import org.jboss.arquillian.container.spi.context.annotation.DeploymentScoped;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.test.spi.annotation.SuiteScoped;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import static org.apache.openejb.cdi.ScopeHelper.startContexts;
import static org.apache.openejb.cdi.ScopeHelper.stopContexts;

public class OpenEJBDeployableContainer implements DeployableContainer<OpenEJBConfiguration> {
    private static final Properties PROPERTIES = new Properties();

    static {
        // global properties
        PROPERTIES.setProperty(Context.INITIAL_CONTEXT_FACTORY, LocalInitialContextFactory.class.getName());
        PROPERTIES.setProperty(LocalInitialContext.ON_CLOSE, LocalInitialContext.Close.DESTROY.name());
        PROPERTIES.setProperty(DeploymentFilterable.DEPLOYMENTS_CLASSPATH_PROPERTY, "false");
        try {
            OpenEJBDeployableContainer.class.getClassLoader().loadClass("org.apache.openejb.server.ServiceManager");
            PROPERTIES.setProperty(OpenEjbContainer.OPENEJB_EMBEDDED_REMOTABLE, "true");
        } catch (Exception e) {
            // ignored
        }
    }

    // config
    private Properties properties;

    // system
    private Assembler assembler;
    private InitialContext initialContext;
    private ContainerSystem containerSystem;
    private ConfigurationFactory configurationFactory;

    // suite
    @Inject
    @DeploymentScoped
    private InstanceProducer<AppInfo> appInfoProducer;

    @Inject
    @DeploymentScoped
    private InstanceProducer<AppContext> appContextProducer;

    @Inject
    @DeploymentScoped
    private InstanceProducer<ServletContext> servletContextProducer;

    @Inject
    @DeploymentScoped
    private InstanceProducer<HttpSession> sessionProducer;

    @Inject
    @ContainerScoped
    private InstanceProducer<ContainerSystem> containerSystemProducer;

    @Inject
    @SuiteScoped
    private Instance<AppModule> module;

    @Inject
    @DeploymentScoped
    private Instance<ServletContext> servletContext;

    @Inject
    @DeploymentScoped
    private Instance<HttpSession> session;

    @Inject
    @DeploymentScoped
    private Instance<AppInfo> info;

    @Inject
    @DeploymentScoped
    private Instance<AppContext> appContext;

    @Override
    public Class<OpenEJBConfiguration> getConfigurationClass() {
        return OpenEJBConfiguration.class;
    }

    @Override
    public void setup(final OpenEJBConfiguration openEJBConfiguration) {
        properties = new Properties();
        final ByteArrayInputStream bais = new ByteArrayInputStream(openEJBConfiguration.getProperties().getBytes());
        try {
            properties.load(bais);
        } catch (IOException e) {
            throw new OpenEJBRuntimeException(e);
        } finally {
            IO.close(bais);
        }
        properties.putAll(PROPERTIES);
    }

    @Override
    public void start() throws LifecycleException {
        try {
            initialContext = new InitialContext(properties);
        } catch (NamingException e) {
            throw new LifecycleException("can't start the OpenEJB container", e);
        }

        assembler = SystemInstance.get().getComponent(Assembler.class);
        containerSystem = SystemInstance.get().getComponent(ContainerSystem.class);
        containerSystemProducer.set(containerSystem);
        configurationFactory = new ConfigurationFactory();
    }

    @Override
    public ProtocolMetaData deploy(final Archive<?> archive) throws DeploymentException {
        try {
            final AppInfo appInfo = configurationFactory.configureApplication(module.get());
            final AppContext appCtx = assembler.createApplication(appInfo);

            final ServletContext appServletContext = new MockServletContext();
            final HttpSession appSession = new MockHttpSession();

            startContexts(appCtx.getWebBeansContext().getContextFactory(), appServletContext, appSession);

            servletContextProducer.set(appServletContext);
            sessionProducer.set(appSession);

            appInfoProducer.set(appInfo);
            appContextProducer.set(appCtx);
        } catch (Exception e) {
            throw new DeploymentException("can't deploy " + archive.getName(), e);
        }
        return new ProtocolMetaData();
    }

    @Override
    public void undeploy(final Archive<?> archive) throws DeploymentException {
        try {
            assembler.destroyApplication(info.get().path);
            stopContexts(appContext.get().getWebBeansContext().getContextFactory(), servletContext.get(), session.get());
        } catch (Exception e) {
            throw new DeploymentException("can't undeploy " + archive.getName(), e);
        }
    }

    @Override
    public void stop() throws LifecycleException {
        try {
            if (initialContext != null) {
                initialContext.close();
            }
        } catch (NamingException e) {
            throw new LifecycleException("can't close the OpenEJB container", e);
        } finally {
            OpenEJB.destroy();
        }
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Local");
    }

    @Override
    public void deploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void undeploy(Descriptor descriptor) throws DeploymentException {
        throw new UnsupportedOperationException();
    }
}
