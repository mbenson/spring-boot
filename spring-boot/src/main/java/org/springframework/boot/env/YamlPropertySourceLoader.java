/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.env;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.YamlProcessor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.bind.PropertySourcesPropertyValues;
import org.springframework.boot.bind.RelaxedDataBinder;
import org.springframework.boot.yaml.SpringProfileDocumentMatcher;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Strategy to load '.yml' (or '.yaml') files into a {@link PropertySource}.
 *
 * @author Dave Syer
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
public class YamlPropertySourceLoader implements PropertySourceLoader, EnvironmentAware {

	private Environment environment;

	@Override
	public String[] getFileExtensions() {
		return new String[] { "yml", "yaml" };
	}

	@Override
	public PropertySource<?> load(String name, Resource resource, String profile)
			throws IOException {
		if (ClassUtils.isPresent("org.yaml.snakeyaml.Yaml", null)) {
			Processor processor = new Processor(resource, profile);

			List<MapPropertySource> documentPropertySources = processor
					.loadDocumentPropertySources();
			if (!documentPropertySources.isEmpty()) {
				if (documentPropertySources.size() == 1) {
					return rename(documentPropertySources.get(0), name);
				}
				ensureNamesAreUnique(documentPropertySources);

				CompositePropertySource result = new CompositePropertySource(name);
				for (MapPropertySource documentPropertySource : documentPropertySources) {
					result.addPropertySource(documentPropertySource);
				}
				return result;
			}
		}
		return null;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	private MapPropertySource rename(MapPropertySource ps, String name) {
		if (ps instanceof ActiveProfileConditionalYamlPropertySource) {
			return ((ActiveProfileConditionalYamlPropertySource) ps).withName(name);
		}
		return new MapPropertySource(name, ps.getSource());
	}

	private void ensureNamesAreUnique(List<MapPropertySource> propertySources) {
		Map<String, AtomicInteger> propertySourceCountByName = new HashMap<String, AtomicInteger>();
		for (MapPropertySource propertySource : propertySources) {
			putIfAbsent(propertySourceCountByName, propertySource.getName(),
					new AtomicInteger());
			propertySourceCountByName.get(propertySource.getName()).incrementAndGet();
		}
		Map<String, AtomicInteger> propertySourceNameMap = new HashMap<String, AtomicInteger>();
		for (ListIterator<MapPropertySource> iter = propertySources.listIterator(); iter
				.hasNext();) {
			MapPropertySource ps = iter.next();
			if (propertySourceCountByName.get(ps.getName()).get() > 1) {
				putIfAbsent(propertySourceNameMap, ps.getName(), new AtomicInteger());
				iter.set(rename(ps, String.format("%s[%d]", ps.getName(),
						propertySourceNameMap.get(ps.getName()).getAndIncrement())));
			}
		}
	}

	private <K, V> V putIfAbsent(Map<K, V> target, K key, V value) {
		if (target.containsKey(key)) {
			return target.get(key);
		}
		return target.put(key, value);
	}

	/**
	 * {@link YamlProcessor} to create a {@link Map} containing the property values.
	 * Similar to {@link YamlPropertiesFactoryBean} but retains the order of entries.
	 */
	private class Processor extends YamlProcessor {
		private final String profile;
		private final Set<String> activeProfiles = new LinkedHashSet<String>();

		Processor(Resource resource, String profile) {
			this.profile = profile;
			setMatchDefault(profile == null);
			setResources(resource);
			Collections.addAll(this.activeProfiles,
					YamlPropertySourceLoader.this.environment.getActiveProfiles());
		}

		@Override
		protected Yaml createYaml() {
			return new Yaml(new StrictMapAppenderConstructor(), new Representer(),
					new DumperOptions(), new Resolver() {
						@Override
						public void addImplicitResolver(Tag tag, Pattern regexp,
								String first) {
							if (tag == Tag.TIMESTAMP) {
								return;
							}
							super.addImplicitResolver(tag, regexp, first);
						}
					});
		}

		List<MapPropertySource> loadDocumentPropertySources() {
			// amass profile data:
			updateProfileDiscoveryDocumentMatchers();

			process(new MatchCallback() {

				@Override
				public void process(Properties properties, Map<String, Object> map) {
					if (updateProfiles(properties)) {
						updateProfileDiscoveryDocumentMatchers();
					}
				}
			});

			final List<MapPropertySource> result = new ArrayList<MapPropertySource>();

			String[] activeProfiles = getActiveProfiles();

			if (this.profile == null) {
				setDocumentMatchers(new SpringProfileDocumentMatcher.ForDefaultProfile(
						activeProfiles));
			}
			else {
				setDocumentMatchers(new SpringProfileDocumentMatcher.ForSpecificProfile(
						this.profile, activeProfiles));
			}
			process(new MatchCallback() {

				@Override
				public void process(Properties properties, Map<String, Object> map) {
					String documentProfiles = properties.getProperty("spring.profiles",
							"(default)");
					String propertySourceName = "YAML [" + documentProfiles + "]";
					Map<String, Object> m = getFlattenedMap(map);
					@SuppressWarnings("unchecked")
					Set<String> negatedProfiles = (Set<String>) properties
							.get(SpringProfileDocumentMatcher.NEGATED_PROFILES_KEY);
					MapPropertySource propertySource;
					if (CollectionUtils.isEmpty(negatedProfiles)) {
						propertySource = new MapPropertySource(propertySourceName, m);
					}
					else {
						propertySource = new ActiveProfileConditionalYamlPropertySource(
								propertySourceName, m,
								YamlPropertySourceLoader.this.environment,
								negatedProfiles);
					}
					result.add(0, propertySource);
				}
			});
			return result;
		}

		private void updateProfileDiscoveryDocumentMatchers() {
			setDocumentMatchers(new SpringProfileDocumentMatcher(getActiveProfiles()));
		}

		private String[] getActiveProfiles() {
			return this.activeProfiles.toArray(new String[this.activeProfiles.size()]);
		}

		private boolean updateProfiles(Properties properties) {
			SpringProfiles springProfiles = extractSpringProfiles(properties);

			// possibly override active profiles from default YAML document:
			boolean active = this.profile == null && this.activeProfiles.isEmpty()
					&& this.activeProfiles.addAll(springProfiles.getActive());

			boolean include = this.activeProfiles.addAll(springProfiles.getInclude());

			return active || include;
		}

		private SpringProfiles extractSpringProfiles(Properties properties) {
			SpringProfiles result = new SpringProfiles();
			MutablePropertySources propertySources = new MutablePropertySources();
			propertySources
					.addFirst(new PropertiesPropertySource("profiles", properties));
			PropertyValues propertyValues = new PropertySourcesPropertyValues(
					propertySources);
			new RelaxedDataBinder(result, "spring.profiles").bind(propertyValues);
			return result;
		}
	}

	/**
	 * Class for binding {@code spring.profiles.*} properties.
	 */
	static class SpringProfiles {
		private List<String> active = new ArrayList<String>();
		private List<String> include = new ArrayList<String>();

		public List<String> getActive() {
			return this.active;
		}

		public void setActive(List<String> active) {
			this.active = active;
		}

		public List<String> getInclude() {
			return this.include;
		}

		public void setInclude(List<String> include) {
			this.include = include;
		}
	}

	private static class ActiveProfileConditionalYamlPropertySource
			extends MapPropertySource {
		private static final String[] EMPTY_STRING_ARRAY = {};

		private final Environment environment;
		private final Set<String> negatedProfiles;

		ActiveProfileConditionalYamlPropertySource(String name,
				Map<String, Object> source, Environment environment,
				Set<String> negatedProfiles) {
			super(name, source);
			this.environment = environment;
			this.negatedProfiles = negatedProfiles;
			source.remove(AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME);
		}

		ActiveProfileConditionalYamlPropertySource withName(String name) {
			return new ActiveProfileConditionalYamlPropertySource(name, this.source,
					this.environment, this.negatedProfiles);
		}

		@Override
		public Object getProperty(String name) {
			if (AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME.equals(name)) {
				return null;
			}
			return isActive() ? super.getProperty(name) : null;
		}

		@Override
		public boolean containsProperty(String name) {
			if (AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME.equals(name)) {
				return false;
			}
			return isActive() && super.containsProperty(name);
		}

		@Override
		public String[] getPropertyNames() {
			return isActive() ? super.getPropertyNames() : EMPTY_STRING_ARRAY;
		}

		private boolean isActive() {
			return Collections.disjoint(this.negatedProfiles,
					Arrays.asList(this.environment.getActiveProfiles()));
		}
	}
}
