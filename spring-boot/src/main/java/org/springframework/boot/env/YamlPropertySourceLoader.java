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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.boot.yaml.SpringProfileDocumentMatcher;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;

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
			Map<String, Object> source = processor.process();
			if (!source.isEmpty()) {
				return new MapPropertySource(name, source);
			}
		}
		return null;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
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
			updateDocumentMatchers();
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

		public Map<String, Object> process() {
			final Map<String, Object> result = new LinkedHashMap<String, Object>();
			process(new MatchCallback() {
				@Override
				public void process(Properties properties, Map<String, Object> map) {
					result.putAll(getFlattenedMap(map));

					// possibly override active profiles from default YAML document:
					if (Processor.this.profile == null
							&& Processor.this.activeProfiles.isEmpty()
							&& properties.containsKey(
									ConfigFileApplicationListener.ACTIVE_PROFILES_PROPERTY)) {
						Set<String> activatedProfiles = new LinkedHashSet<String>(
								extractActiveProfiles(properties));
						if (Processor.this.activeProfiles.addAll(activatedProfiles)) {
							updateDocumentMatchers();
						}
					}
				}
			});
			return result;
		}

		private void updateDocumentMatchers() {
			String[] activeProfiles = this.activeProfiles
					.toArray(new String[this.activeProfiles.size()]);
			if (this.profile == null) {
				setDocumentMatchers(new SpringProfileDocumentMatcher.ForDefaultProfile(
						activeProfiles));
			}
			else {
				setDocumentMatchers(new SpringProfileDocumentMatcher.ForSpecificProfile(
						this.profile, activeProfiles));
			}
		}

		private List<String> extractActiveProfiles(Properties properties) {
			SpringProfiles springProperties = new SpringProfiles();
			MutablePropertySources propertySources = new MutablePropertySources();
			propertySources
					.addFirst(new PropertiesPropertySource("profiles", properties));
			PropertyValues propertyValues = new PropertySourcesPropertyValues(
					propertySources);
			new RelaxedDataBinder(springProperties, "spring.profiles")
					.bind(propertyValues);
			return springProperties.getActive();
		}

	}

	/**
	 * Class for binding {@code spring.profiles.active} property.
	 */
	static class SpringProfiles {
		private List<String> active = new ArrayList<String>();

		public List<String> getActive() {
			return this.active;
		}

		public void setActive(List<String> active) {
			this.active = active;
		}
	}

}
