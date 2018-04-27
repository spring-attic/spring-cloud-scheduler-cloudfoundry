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

import io.pivotal.reactor.scheduler.ReactorSchedulerClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundry2630AndLaterTaskLauncher;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.scheduler.spi.core.Scheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates a {@link CloudFoundryAppScheduler}.
 *
 * @author Glenn Renfro
 */
@Configuration
@EnableConfigurationProperties
public class CloudFoundrySchedulerAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ReactorSchedulerClient reactorSchedulerClient(ConnectionContext context,
			TokenProvider passwordGrantTokenProvider,
			CloudFoundrySchedulerProperties properties) {
		return ReactorSchedulerClient.builder()
				.connectionContext(context)
				.tokenProvider(passwordGrantTokenProvider)
				.root(Mono.just(properties.getSchedulerUrl()))
				.build();
	}

	@Bean
	@ConditionalOnMissingBean
	public Scheduler scheduler(ReactorSchedulerClient client,
			CloudFoundryOperations operations,
			CloudFoundryConnectionProperties properties,
			TaskLauncher taskLauncher) {
		return new CloudFoundryAppScheduler(client, operations, properties,
				(CloudFoundry2630AndLaterTaskLauncher)taskLauncher);
	}
	@Bean
	@ConditionalOnMissingBean
	public CloudFoundrySchedulerProperties cloudFoundrySchedulerProperties() {
		return new CloudFoundrySchedulerProperties();
	}

}
