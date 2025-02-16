package io.jenkins.plugins.casc.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Describable;
import hudson.model.Descriptor;
import io.jenkins.plugins.casc.Configurable;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.RootElementConfigurator;
import io.jenkins.plugins.casc.impl.configurators.ConfigurableConfigurator;
import io.jenkins.plugins.casc.impl.configurators.DataBoundConfigurator;
import io.jenkins.plugins.casc.impl.configurators.DescriptorConfigurator;
import io.jenkins.plugins.casc.impl.configurators.EnumConfigurator;
import io.jenkins.plugins.casc.impl.configurators.ExtensionConfigurator;
import io.jenkins.plugins.casc.impl.configurators.HeteroDescribableConfigurator;
import io.jenkins.plugins.casc.impl.configurators.PrimitiveConfigurator;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jvnet.tiger_types.Types;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
@Restricted(NoExternalUse.class)
public class DefaultConfiguratorRegistry implements ConfiguratorRegistry {

    private static final Logger LOGGER = Logger.getLogger(DefaultConfiguratorRegistry.class.getName());


    @Override
    @CheckForNull
    public RootElementConfigurator lookupRootElement(String name) {
        for (RootElementConfigurator c : RootElementConfigurator.all()) {
            if (c.getName().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Looks for a configurator for exact type.
     * @param type Type
     * @return Configurator
     * @throws ConfiguratorException Configurator is not found
     */
    @Override
    @NonNull
    public Configurator lookupOrFail(Type type) throws ConfiguratorException {
        Configurator configurator = cache.get(type);
        if (configurator == null) {
            throw new ConfiguratorException("Cannot find configurator for type " + type);
        }
        return configurator;
    }

    /**
     * Looks for a configurator for exact type.
     * @param type Type
     * @return Configurator or {@code null} if it is not found
     */
    @Override
    @CheckForNull
    public Configurator lookup(Type type) {
        return cache.get(type);
    }

    private LoadingCache<Type, Configurator> cache = Caffeine.newBuilder()
            .expireAfterAccess(10L, TimeUnit.SECONDS)
            .build(type -> internalLookup(type));

    private Configurator internalLookup(Type type) {
        Class clazz = Types.erasure(type);

        final Jenkins jenkins = Jenkins.get();
        final ExtensionList<Configurator> l = jenkins.getExtensionList(Configurator.class);
        for (Configurator c : l) {
            if (c.canConfigure(clazz)) {
                // this type has a dedicated Configurator implementation
                return c;
            }
        }

        //TODO: Only try to cast if we can actually get the parameterized type
        if (Collection.class.isAssignableFrom(clazz) && type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Type actualType = pt.getActualTypeArguments()[0];
            if (actualType instanceof WildcardType) {
                actualType = ((WildcardType) actualType).getUpperBounds()[0];
            }
            if (actualType instanceof ParameterizedType) {
                actualType = ((ParameterizedType) actualType).getRawType();
            }
            if (!(actualType instanceof Class)) {
                throw new IllegalStateException("Can't handle " + type);
            }
            return internalLookup(actualType); // cache is not reëntrant
        }

        if (Configurable.class.isAssignableFrom(clazz)) {
            return new ConfigurableConfigurator(clazz);
        }

        if (Descriptor.class.isAssignableFrom(clazz)) {
            ExtensionList extensions = jenkins.getExtensionList(clazz);
            if (!extensions.isEmpty()) {
                return new DescriptorConfigurator((Descriptor) extensions.get(0));
            }
        }

        if (DataBoundConfigurator.getDataBoundConstructor(clazz) != null) {
            return new DataBoundConfigurator(clazz);
        }

        if (Modifier.isAbstract(clazz.getModifiers()) && Describable.class.isAssignableFrom(clazz)) {
            // this is a jenkins Describable component, with various implementations
            return new HeteroDescribableConfigurator(clazz);
        }

        if (Extension.class.isAssignableFrom(clazz)) {
            return new ExtensionConfigurator(clazz);
        }

        if (Stapler.lookupConverter(clazz) != null) {
            return new PrimitiveConfigurator(clazz);
        }

        if (clazz.isEnum()) {
            return new EnumConfigurator(clazz);
        }

        LOGGER.warning("Configuration-as-Code can't handle type "+ type);
        return null;
    }


}
