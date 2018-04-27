/*
 * Copyright 2018 the original author or authors.
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

package org.springframework.cloud.scheduler.spi.cloudfoundry;

import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundry2630AndLaterTaskLauncher;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.scheduler.spi.core.Scheduler;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the proper Scheduler bean was created by the CloudFoundrySchedulerAutoConfiguration.
 *
 * @author Glenn Renfro
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {CloudFoundrySchedulerAutoConfiguration.class,
		CloudFoundrySchedulerAutoConfigurationTests.AutoConfigurationTestConfiguration.class})
@TestPropertySource(properties = {
		"spring.cloud.scheduler.cloudfoundry.schedulerUrl=http://somewhere",
})
public class CloudFoundrySchedulerAutoConfigurationTests {
	@Autowired
	private ConfigurableApplicationContext context;

	@Test
	public void testConfiguration() throws Exception {
		Scheduler scheduler = this.context.getBean(Scheduler.class);
		assertNotNull("scheduler should not be null", scheduler);
		assertThat(scheduler.getClass()).isEqualTo(CloudFoundryAppScheduler.class);
	}

	@Configuration
	public static class AutoConfigurationTestConfiguration {

		@Bean
		public ConnectionContext connectionContext() {
			return mock(ConnectionContext.class);
		}

		@Bean
		public TokenProvider tokenProvider() {
			return mock(TokenProvider.class);
		}

		@Bean
		public CloudFoundryOperations cloudFoundryOperations() {
			return mock(CloudFoundryOperations.class);
		}

		@Bean
		public CloudFoundryConnectionProperties connectionProperties() {
			return mock(CloudFoundryConnectionProperties.class);
		}

		@Bean
		public TaskLauncher taskLauncher() {
			return mock(CloudFoundry2630AndLaterTaskLauncher.class);
		}
	}
}
