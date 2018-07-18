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

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import io.jsonwebtoken.lang.Assert;
import io.pivotal.reactor.util.PaginationUtils;
import io.pivotal.scheduler.SchedulerClient;
import io.pivotal.scheduler.v1.jobs.CreateJobRequest;
import io.pivotal.scheduler.v1.jobs.DeleteJobRequest;
import io.pivotal.scheduler.v1.jobs.Job;
import io.pivotal.scheduler.v1.jobs.ListJobsRequest;
import io.pivotal.scheduler.v1.jobs.ListJobsResponse;
import io.pivotal.scheduler.v1.jobs.ScheduleJobRequest;
import io.pivotal.scheduler.v1.schedules.ExpressionType;
import javax.net.ssl.SSLException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.AbstractApplicationSummary;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.spaces.SpaceSummary;
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
import org.springframework.cloud.scheduler.spi.core.UnScheduleException;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * A Cloud Foundry implementation of the Scheduler interface.
 *
 * @author Glenn Renfro
 */
public class CloudFoundryAppScheduler implements Scheduler {

	private final static int PCF_PAGE_START_NUM = 1; //First PageNum for PCFScheduler starts at 1.

	protected final static Log logger = LogFactory.getLog(CloudFoundryAppScheduler.class);
	private final SchedulerClient client;
	private final CloudFoundryOperations operations;
	private final CloudFoundryConnectionProperties properties;
	private final CloudFoundry2630AndLaterTaskLauncher taskLauncher;
	private final CloudFoundrySchedulerProperties schedulerProperties;

	public CloudFoundryAppScheduler(SchedulerClient client, CloudFoundryOperations operations,
			CloudFoundryConnectionProperties properties, CloudFoundry2630AndLaterTaskLauncher taskLauncher,
			CloudFoundrySchedulerProperties schedulerProperties) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(operations, "operations must not be null");
		Assert.notNull(properties, "properties must not be null");
		Assert.notNull(taskLauncher, "taskLauncher must not be null");
		Assert.notNull(schedulerProperties, "schedulerProperties must not be null");

		this.client = client;
		this.operations = operations;
		this.properties = properties;
		this.taskLauncher = taskLauncher;
		this.schedulerProperties = schedulerProperties;
	}

	@Override
	public void schedule(ScheduleRequest scheduleRequest) {
		String appName = scheduleRequest.getDefinition().getName();
		String scheduleName = scheduleRequest.getScheduleName();
		logger.debug(String.format("Scheduling: %s", scheduleName));

		String command = stageTask(scheduleRequest);

		String cronExpression = scheduleRequest.getSchedulerProperties().get(SchedulerPropertyKeys.CRON_EXPRESSION);
		Assert.hasText(cronExpression, String.format(
				"request's scheduleProperties must have a %s that is not null nor empty",
				SchedulerPropertyKeys.CRON_EXPRESSION));
		retryTemplate().execute(e -> {
			scheduleTask(appName, scheduleName, cronExpression, command);
			return null;
		});
	}

	@Override
	public void unschedule(String scheduleName) {
		logger.debug(String.format("Unscheduling: %s", scheduleName));
		this.client.jobs().delete(DeleteJobRequest.builder()
				.jobId(getJob(scheduleName))
				.build())
				.block(Duration.ofSeconds(schedulerProperties.getUnScheduleTimeoutInSeconds()));
	}

	@Override
	public List<ScheduleInfo> list(String taskDefinitionName) {
		return list().stream().filter(scheduleInfo ->
				scheduleInfo.getTaskDefinitionName().equals(taskDefinitionName))
				.collect(Collectors.toList());
	}

	@Override
	public List<ScheduleInfo> list() {
		Flux<ApplicationSummary> applicationSummaries = cacheAppSummaries();

		return this.getSpace(this.properties.getSpace())
				.map(SpaceSummary::getId)
				.flatMapMany(spaceId -> requestListJobs(client, spaceId))
				.flatMap(job -> getApplication(applicationSummaries, job.getApplicationId()) // get the application name for each job.
						.map(optionalApp -> getScheduleInfo(job, optionalApp)))
				.collectList()
				.block(Duration.ofSeconds(schedulerProperties.getListTimeoutInSeconds()));
	}

	/**
	 * Schedules the job for the application.
	 * @param appName The name of the task app to be scheduled.
	 * @param scheduleName the name of the schedule.
	 * @param expression the cron expression.
	 * @param command the command returned from the staging.
	 */
	private void scheduleTask(String appName, String scheduleName, String expression, String command) {
		logger.debug(String.format("Scheduling Task: ", appName));
		getApplicationByAppName(appName)
				.flatMap(abstractApplicationSummary -> this.client.jobs().create(CreateJobRequest.builder()
						.applicationId(abstractApplicationSummary.getId()) // App GUID
						.command(command)
						.name(scheduleName)
						.build())).flatMap(createJobResponse -> this.client.jobs().schedule(ScheduleJobRequest.
				builder().
				jobId(createJobResponse.getId()).
				expression(expression).
				expressionType(ExpressionType.CRON).
				enabled(true).
				build()))
				.onErrorMap(e -> {
					if (e instanceof SSLException) {
						throw new CloudFoundryScheduleSSLException("Failed to schedule" + scheduleName, e);
					}
					else {
						throw new CreateScheduleException("Failed to schedule: " + scheduleName, e);
					}
				})
				.block(Duration.ofSeconds(schedulerProperties.getScheduleTimeoutInSeconds()));
	}

	/**
	 * Stages the application specified in the {@link ScheduleRequest} on the CF server.
	 * @param scheduleRequest {@link ScheduleRequest} containing the information required to schedule a task.
	 * @return the command string for the scheduled task.
	 */
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

	/**
	 * Retrieve a {@link Mono} containing the {@link ApplicationSummary} associated with the appId.
	 * @param appName the name of the {@link AbstractApplicationSummary} to search.
	 */
	private Mono<AbstractApplicationSummary> getApplicationByAppName(String appName) {
		return requestListApplications()
				.filter(application -> appName.equals(application.getName()))
				.singleOrEmpty()
				.cast(AbstractApplicationSummary.class);
	}

	/**
	 * Retrieve a {@link Flux} of {@link ApplicationSummary}s.
	 */
	private Flux<ApplicationSummary> requestListApplications() {
		return this.operations.applications()
				.list();
	}

	/**
	 * Retrieve a {@link Flux} of {@link Job}s.
	 */
	private Flux<Job> requestListJobs(SchedulerClient client, String spaceId) {
		return PaginationUtils
				.requestResources(page -> client.jobs()
						.list(ListJobsRequest.builder()
								.detailed(true)
								.page(page)
								.spaceId(spaceId)
								.build()));
	}

	/**
	 *
	 * @param job the {@Link Job} containing information for the {@Link ScheduleInfo}
	 * @param app the {@Link ApplicationSummary} application associated with the {@Link Job}
	 * @return
	 */
	private ScheduleInfo getScheduleInfo(Job job, ApplicationSummary app) {
		ScheduleInfo scheduleInfo = new ScheduleInfo();
		scheduleInfo.setScheduleProperties(new HashMap<>());
		scheduleInfo.setScheduleName(job.getName());
		scheduleInfo.setTaskDefinitionName(app.getName());
		if (job.getJobSchedules() != null) {
			scheduleInfo.getScheduleProperties().put(SchedulerPropertyKeys.CRON_EXPRESSION,
					job.getJobSchedules().get(0).getExpression());
		} else {
			logger.warn(String.format("Job %s does not have an associated schedule", job.getName()));
		}
		return scheduleInfo;
	}

	/**
	 * Retrieve a cached {@link Flux} of {@link ApplicationSummary}s.
	 */
	private Flux<ApplicationSummary> cacheAppSummaries() {
		return requestListApplications()
				.cache(); //cache results from first call.  No need to re-retrieve each time.
	}

	/**
	 * Retrieve a {@link Flux} containing the available {@link SpaceSummary}s.
	 * @return {@link Flux} of {@link SpaceSummary}s.
	 */
	private Flux<SpaceSummary> requestSpaces() {
		return this.operations.spaces()
				.list();
	}

	/**
	 * Retrieve a {@link Mono} containing a {@link SpaceSummary} for the specified name.
	 * @param spaceName the name of space to search.
	 * @return the {@link SpaceSummary} associated with the spaceName.
	 */
	private Mono<SpaceSummary> getSpace(String spaceName) {
		return requestSpaces()
				.cache() //cache results from first call.
				.filter(space -> spaceName.equals(space.getName()))
				.singleOrEmpty()
				.cast(SpaceSummary.class);
	}

	/**
	 * Retrieve a {@link Mono} containing the {@link ApplicationSummary} associated with the appId.
	 * @param applicationSummaries {@link Flux} of {@link ApplicationSummary}s to filter.
	 * @param appId the id of the {@link ApplicationSummary} to search.
	 */
	private Mono<ApplicationSummary> getApplication(Flux<ApplicationSummary> applicationSummaries, String appId) {
		return applicationSummaries
				.filter(application -> appId.equals(application.getId()))
				.singleOrEmpty();
	}

	/**
	 * Retrieves the number of pages that can be returned when retrieving a list of jobs.
	 * @return an int containing the number of available pages.
	 */
	private int getJobPageCount() {
		ListJobsResponse response = this.getSpace(this.properties.getSpace()).flatMap(requestSummary -> {
			return this.client.jobs().list(ListJobsRequest.builder()
					.spaceId(requestSummary.getId())
					.detailed(false).build());
		}).block();
		return response.getPagination().getTotalPages();
	}

	/**
	 * Retrieve a {@link Mono} that contains the {@link Job} for the jobName or null.
	 * @param jobName - the name of the job to search search.
	 * @param page - the page to search.
	 * @return {@link Mono} containing the {@link Job} if found or null if not found.
	 */
	private Mono<Job> getJobMono(String jobName, int page) {
		return this.getSpace(this.properties.getSpace()).flatMap(requestSummary -> this.client
				.jobs()
				.list(ListJobsRequest.builder()
						.spaceId(requestSummary.getId())
						.page(page)
						.build()))
				.flatMapIterable(ListJobsResponse::getResources)
				.filter(job -> job.getName().equals(jobName))
				.singleOrEmpty();// iterate over the resources returned.
	}

	/**
	 * Retrieve the job id for the specified PCF Job Name.
	 * @param jobName the name of the job to search.
	 * @return The job id associated with the job.
	 */
	private String getJob(String jobName) {
		Job result = null;
		final int pageCount = getJobPageCount();
		for (int pageNum = PCF_PAGE_START_NUM; pageNum <= pageCount; pageNum++) {
			result = getJobMono(jobName, pageNum)
					.block();
			if (result != null) {
				break;
			}
		}
		if(result == null) {
			throw new UnScheduleException(String.format("task. %s, was not found", jobName));
		}
		return result.getId();
	}

	private RetryTemplate retryTemplate() {
		RetryTemplate retryTemplate = new RetryTemplate();
		SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
				schedulerProperties.getScheduleSSLRetryCount(),
				Collections.singletonMap(CloudFoundryScheduleSSLException.class, true));
		retryTemplate.setRetryPolicy(retryPolicy);
		return retryTemplate;
	}
}
