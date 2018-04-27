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

import java.util.List;

import io.jsonwebtoken.lang.Assert;
import io.pivotal.scheduler.SchedulerClient;
import io.pivotal.scheduler.v1.ExpressionType;
import io.pivotal.scheduler.v1.jobs.CreateJobRequest;
import io.pivotal.scheduler.v1.jobs.ScheduleJobRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.AbstractApplicationSummary;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundry2630AndLaterTaskLauncher;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.scheduler.spi.core.CreateScheduleException;
import org.springframework.cloud.scheduler.spi.core.ScheduleInfo;
import org.springframework.cloud.scheduler.spi.core.ScheduleRequest;
import org.springframework.cloud.scheduler.spi.core.Scheduler;
import org.springframework.cloud.scheduler.spi.core.SchedulerPropertyKeys;

/**
 * A Cloud Foundry implementation of the Scheduler interface.
 *
 * @author Glenn Renfro
 */
public class CloudFoundryAppScheduler implements Scheduler {

	private final SchedulerClient client;

	private final CloudFoundryOperations operations;

	private final CloudFoundryConnectionProperties properties;

	private final CloudFoundry2630AndLaterTaskLauncher taskLauncher;

	protected static final Log logger = LogFactory.getLog(CloudFoundryAppScheduler.class);

	public CloudFoundryAppScheduler(SchedulerClient client, CloudFoundryOperations operations,
			CloudFoundryConnectionProperties properties, CloudFoundry2630AndLaterTaskLauncher taskLauncher) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(operations, "operations must not be null");
		Assert.notNull(properties, "properties must not be null");
		Assert.notNull(taskLauncher, "taskLauncher must not be null");

		this.client = client;
		this.operations = operations;
		this.properties = properties;
		this.taskLauncher = taskLauncher;
	}

	@Override
	public void schedule(ScheduleRequest scheduleRequest) {
		String appName = scheduleRequest.getDefinition().getName();
		String jobName = scheduleRequest.getScheduleName();
		String command = stageTask(scheduleRequest);

		String cronExpression = scheduleRequest.getSchedulerProperties().get(SchedulerPropertyKeys.CRON_EXPRESSION);
		Assert.hasText(cronExpression, String.format(
				"request's scheduleProperties must have a %s that is not null nor empty",
				SchedulerPropertyKeys.CRON_EXPRESSION));
		scheduleJob(appName, jobName, cronExpression, command);
	}

	@Override
	public void unschedule(String scheduleName) {
		throw new UnsupportedOperationException("Interface is not implemented for unschedule method.");
	}

	@Override
	public List<ScheduleInfo> list(String taskDefinitionName) {
		throw new UnsupportedOperationException("Interface is not implemented for list method.");
	}

	@Override
	public List<ScheduleInfo> list() {
		throw new UnsupportedOperationException("Interface is not implemented for list method.");
	}

	private void scheduleJob(String appName, String scheduleName, String expression, String command) {
		logger.debug(String.format("Scheduling Task: ", appName));
		getApplicationByAppName(appName)
				.flatMap(abstractApplicationSummary -> {
					return this.client.jobs().create(CreateJobRequest.builder()
							.applicationId(abstractApplicationSummary.getId()) // App GUID
							.command(command)
							.name(scheduleName)
							.build());
				}).flatMap(createJobResponse -> {
			return this.client.jobs().schedule(ScheduleJobRequest.
					builder().
					jobId(createJobResponse.getId()).
					expression(expression).
					expressionType(ExpressionType.CRON).
					enabled(true).
					build());
		})
				.onErrorMap(e -> {
					throw new CreateScheduleException("Failed to schedule: " + scheduleName, e);
				})
				.block();
	}

	private String stageTask(ScheduleRequest scheduleRequest) {
		logger.debug(String.format("Staging Task: ",
				scheduleRequest.getDefinition().getName()));
		AppDeploymentRequest request = new AppDeploymentRequest(
				scheduleRequest.getDefinition(),
				scheduleRequest.getResource(),
				scheduleRequest.getDeploymentProperties());
		SummaryApplicationResponse response = taskLauncher.stage(request);
		return taskLauncher.getCommand(response, request);
	}

	private Mono<AbstractApplicationSummary> getApplicationByAppName(String appName) {
		return requestListApplications()
				.log()
				.filter(application -> appName.equals(application.getName()))
				.log()
				.singleOrEmpty()
				.cast(AbstractApplicationSummary.class);
	}

	private Flux<ApplicationSummary> requestListApplications() {
		return this.operations.applications()
				.list();
	}

}
