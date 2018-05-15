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

import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;

import io.jsonwebtoken.lang.Assert;
import io.pivotal.scheduler.SchedulerClient;
import io.pivotal.scheduler.v1.jobs.DeleteJobRequest;
import io.pivotal.scheduler.v1.jobs.ListJobSchedulesRequest;
import io.pivotal.scheduler.v1.jobs.ListJobSchedulesResponse;
import io.pivotal.scheduler.v1.jobs.ListJobsRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.spaces.SpaceSummary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.scheduler.spi.core.ScheduleInfo;
import org.springframework.cloud.scheduler.spi.core.ScheduleRequest;
import org.springframework.cloud.scheduler.spi.core.Scheduler;
import org.springframework.cloud.scheduler.spi.core.SchedulerException;
import org.springframework.cloud.scheduler.spi.core.SchedulerPropertyKeys;

/**
 * A Cloud Foundry implementation of the Scheduler interface
 *
 * @author Glenn Renfro
 */
public class CloudFoundryAppScheduler implements Scheduler {

	final private SchedulerClient client;

	final private CloudFoundryOperations operations;

	final private CloudFoundryConnectionProperties properties;

	protected static final Log logger = LogFactory.getLog(CloudFoundryAppScheduler.class);

	public CloudFoundryAppScheduler(SchedulerClient client, CloudFoundryOperations operations,
			CloudFoundryConnectionProperties properties) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(operations, "operations must not be null");
		Assert.notNull(properties, "properties must not be null");

		this.client = client;
		this.operations = operations;
		this.properties = properties;
	}

	@Override
	public void schedule(ScheduleRequest scheduleRequest) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void unschedule(String scheduleName) {
		getJob(scheduleName).flatMap(scheduleJobInfo -> {
			return this.client.jobs().delete(DeleteJobRequest.builder()
					.jobId(scheduleJobInfo.getJobId())
					.build()); })
				.onErrorMap(e -> {
					if (e instanceof NoSuchElementException) {
						throw new SchedulerException(String.format("Failed to unschedule, schedule %s does not exist.", scheduleName), e);
					}
					throw new SchedulerException("Failed to unschedule: " + scheduleName, e); })
				.block();
	}

	@Override
	public List<ScheduleInfo> list(String taskDefinitionName) {
		return getSchedules().filter(scheduleInfo ->
				scheduleInfo.getTaskDefinitionName().equals(taskDefinitionName))
				.collectList()
				.block();
	}

	@Override
	public List<ScheduleInfo> list() {
		return getSchedules()
				.collectList()
				.block();
	}

	private Flux<ScheduleJobInfo> getJobs() {
		Flux<ApplicationSummary> applicationSummaries = cacheAppSummaries();
		return this.getSpace(this.properties.getSpace()).flatMap(requestSummary -> {
			return this.client
					.jobs()
					.list(ListJobsRequest.builder()
							.spaceId(requestSummary.getId())
							.build()); })
				.flatMapIterable(jobs -> jobs.getResources())// iterate over the resources returned.
				.flatMap(job -> {
					return getApplication(applicationSummaries,
							job.getApplicationId()) // get the application name for each job.
							.map(optionalApp -> {
								ScheduleJobInfo scheduleJobInfo = new ScheduleJobInfo();
								scheduleJobInfo.setScheduleProperties(new HashMap<>());
								scheduleJobInfo.setScheduleName(job.getName());
								scheduleJobInfo.setTaskDefinitionName(optionalApp.getName());
								scheduleJobInfo.setJobId(job.getId());
								return scheduleJobInfo;
							});
				});
	}

	private Flux<ScheduleInfo> getSchedules() {
		return getJobs()
				.flatMap(scheduleJobInfo -> { //iterate over each job and retrieve its schedule expression
					return getScheduleExpression(scheduleJobInfo.getJobId())
							.map(listJobSchedulesResponse -> {
								scheduleJobInfo.getScheduleProperties().put(
										SchedulerPropertyKeys.CRON_EXPRESSION,
										listJobSchedulesResponse.getResources().get(0).getExpression());
								ScheduleInfo scheduleInfo = new ScheduleInfo();
								scheduleInfo.setTaskDefinitionName(scheduleJobInfo.getTaskDefinitionName());
								scheduleInfo.setScheduleProperties(scheduleJobInfo.getScheduleProperties());
								scheduleInfo.setScheduleName(scheduleJobInfo.getScheduleName());
								return (scheduleInfo);
							})
							.onErrorResume(e -> { // if job does not have a schedule associated with it.  Return schedule info with no properties.
								this.logger.warn(String.format(
										"%s does not have a schedule expression associated with it.",
										scheduleJobInfo.getScheduleName()));
								ScheduleInfo scheduleInfo = new ScheduleInfo();
								scheduleInfo.setScheduleName(scheduleJobInfo.getScheduleName());
								scheduleInfo.setTaskDefinitionName(scheduleJobInfo.getTaskDefinitionName());
								scheduleInfo.setScheduleProperties(scheduleJobInfo.getScheduleProperties());
								return Mono.just(scheduleInfo);
							});
				});
	}

	public Mono<ScheduleJobInfo> getJob(String scheduleName) {
		return getJobs().filter(scheduleInfo ->
				scheduleInfo.getScheduleName().equals(scheduleName))
				.single();
	}


	public Mono<ListJobSchedulesResponse> getScheduleExpression(String jobId) {
		return this.client.jobs()
				.listSchedules(
						ListJobSchedulesRequest
								.builder()
								.jobId(jobId)
								.build());
	}

	private Flux<ApplicationSummary> cacheAppSummaries() {
		return requestListApplications()
				.cache(); //cache results from first call.  No need to re-retrieve each time.
	}

	private Mono<ApplicationSummary> getApplication(Flux<ApplicationSummary> applicationSummaries,
			String appId) {
		return applicationSummaries
				.filter(application -> appId.equals(application.getId()))
				.singleOrEmpty();

	}

	private Flux<ApplicationSummary> requestListApplications() {
		return this.operations.applications()
				.list();
	}

	private Mono<SpaceSummary> getSpace(String spaceName) {
		return requestSpaces()
				.cache() //cache results from first call.
				.filter(space -> spaceName.equals(space.getName()))
				.singleOrEmpty()
				.cast(SpaceSummary.class);
	}

	private Flux<SpaceSummary> requestSpaces() {
		return this.operations.spaces()
				.list();
	}
}
