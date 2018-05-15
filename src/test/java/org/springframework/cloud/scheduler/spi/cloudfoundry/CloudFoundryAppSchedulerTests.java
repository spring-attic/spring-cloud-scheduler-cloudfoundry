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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.pivotal.scheduler.SchedulerClient;
import io.pivotal.scheduler.v1.ExpressionType;
import io.pivotal.scheduler.v1.calls.Calls;
import io.pivotal.scheduler.v1.jobs.CreateJobRequest;
import io.pivotal.scheduler.v1.jobs.CreateJobResponse;
import io.pivotal.scheduler.v1.jobs.DeleteJobRequest;
import io.pivotal.scheduler.v1.jobs.DeleteJobScheduleRequest;
import io.pivotal.scheduler.v1.jobs.ExecuteJobRequest;
import io.pivotal.scheduler.v1.jobs.ExecuteJobResponse;
import io.pivotal.scheduler.v1.jobs.GetJobRequest;
import io.pivotal.scheduler.v1.jobs.GetJobResponse;
import io.pivotal.scheduler.v1.jobs.JobResource;
import io.pivotal.scheduler.v1.jobs.JobScheduleResource;
import io.pivotal.scheduler.v1.jobs.Jobs;
import io.pivotal.scheduler.v1.jobs.ListJobHistoriesRequest;
import io.pivotal.scheduler.v1.jobs.ListJobHistoriesResponse;
import io.pivotal.scheduler.v1.jobs.ListJobScheduleHistoriesRequest;
import io.pivotal.scheduler.v1.jobs.ListJobScheduleHistoriesResponse;
import io.pivotal.scheduler.v1.jobs.ListJobSchedulesRequest;
import io.pivotal.scheduler.v1.jobs.ListJobSchedulesResponse;
import io.pivotal.scheduler.v1.jobs.ListJobsRequest;
import io.pivotal.scheduler.v1.jobs.ListJobsResponse;
import io.pivotal.scheduler.v1.jobs.ScheduleJobRequest;
import io.pivotal.scheduler.v1.jobs.ScheduleJobResponse;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.applications.ApplicationsV3;
import org.cloudfoundry.client.v3.tasks.Tasks;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.spaces.SpaceSummary;
import org.cloudfoundry.operations.spaces.Spaces;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties;
import org.springframework.cloud.scheduler.spi.core.ScheduleInfo;
import org.springframework.cloud.scheduler.spi.core.SchedulerException;
import org.springframework.cloud.scheduler.spi.core.SchedulerPropertyKeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Test the core features of the Spring Cloud Scheduler implementation.
 *
 * @author Glenn Renfro
 */
public class CloudFoundryAppSchedulerTests {

	public static final String DEFAULT_CRON_EXPRESSION = "* * * * *";

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Applications applications;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryOperations operations;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Spaces spaces;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private ApplicationsV3 applicationsV3;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private CloudFoundryClient cloudFoundryClient;

	@Mock(answer = Answers.RETURNS_SMART_NULLS)
	private Tasks tasks;

	private CloudFoundryAppScheduler cloudFoundryAppScheduler;

	private SchedulerClient client;

	private CloudFoundryConnectionProperties properties = new CloudFoundryConnectionProperties();


	@Before
	public void setUp() throws IOException {
		MockitoAnnotations.initMocks(this);
		given(this.cloudFoundryClient.applicationsV3()).willReturn(this.applicationsV3);
		given(this.cloudFoundryClient.tasks()).willReturn(this.tasks);
		given(this.spaces.list()).willReturn(getTestSpaces());

		this.properties.setSpace("test-space");

		given(this.operations.applications()).willReturn(this.applications);
		given(this.operations.spaces()).willReturn(this.spaces);

		this.client = new TestSchedulerClient();

		this.cloudFoundryAppScheduler = new CloudFoundryAppScheduler(this.client, this.operations,
				this.properties);
	}

	@Test
	public void testDelete() {
		mockAppResultsInAppList();
		mockSingleJobInJobList();
		List<ScheduleInfo> result = this.cloudFoundryAppScheduler.list();
		assertThat(result.size()).isEqualTo(2);

		this.cloudFoundryAppScheduler.unschedule("test-job-name-1");
		result = this.cloudFoundryAppScheduler.list();
		assertThat(result.size()).isEqualTo(1);

		assertThat(result.get(0).getScheduleName()).isEqualTo("test-job-name-2");
		assertThat(result.get(0).getTaskDefinitionName()).isEqualTo("test-application-2");
	}

	@Test
	public void testMissingScheduleDelete() {
		boolean exceptionFired = false;
		mockAppResultsInAppList();
		mockSingleJobInJobList();
		try {
			this.cloudFoundryAppScheduler.unschedule("test-job-name-3");
		}
		catch (SchedulerException se) {
			assertThat(se.getMessage()).isEqualTo("Failed to unschedule, schedule test-job-name-3 does not exist.");
			exceptionFired = true;
		}
		assertThat(exceptionFired).isTrue();
	}

	@Test
	public void testListWithNoSchedules() {
		given(this.operations.applications()
				.list())
				.willReturn(Flux.empty());
		List<ScheduleInfo> result = this.cloudFoundryAppScheduler.list();
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	public void testListSchedules() {
		mockSingleJobInJobList();
		mockSingleSchedule();
		mockAppResultsInAppList();
		List<ScheduleInfo> result = this.cloudFoundryAppScheduler.list();
		assertThat(result.size()).isEqualTo(2);
		verifyScheduleInfo(result.get(0), "test-application-1", "test-job-name-1", DEFAULT_CRON_EXPRESSION);
		verifyScheduleInfo(result.get(1), "test-application-2", "test-job-name-2", DEFAULT_CRON_EXPRESSION);
	}

	@Test
	public void testListSchedulesWithAppName() {
		mockSingleJobInJobList();
		mockSingleSchedule();
		mockAppResultsInAppList();
		List<ScheduleInfo> result = this.cloudFoundryAppScheduler.list("test-application-2");
		assertThat(result.size()).isEqualTo(1);
		verifyScheduleInfo(result.get(0), "test-application-2", "test-job-name-2", DEFAULT_CRON_EXPRESSION);
	}

	@Test
	public void testListSchedulesWithInvalidAppName() {
		mockSingleJobInJobList();
		mockSingleSchedule();
		mockAppResultsInAppList();
		List<ScheduleInfo> result = this.cloudFoundryAppScheduler.list("not-here");
		assertThat(result.size()).isEqualTo(0);
	}

	@Test
	public void testListScheduleNoExpression() {
		mockSingleJobInJobList();
		mockAppResultsInAppList();
		List<ScheduleInfo> result = this.cloudFoundryAppScheduler.list();
		assertThat(result.size()).isEqualTo(2);
		verifyScheduleInfo(result.get(0), "test-application-1", "test-job-name-1", null);
		verifyScheduleInfo(result.get(1), "test-application-2", "test-job-name-2", null);
	}

	private void givenRequestListApplications(Flux<ApplicationSummary> response) {
		given(this.operations.applications()
				.list())
				.willReturn(response);
	}

	private static class TestSchedulerClient implements SchedulerClient {
		private Jobs jobs;

		public TestSchedulerClient() {
			jobs = new TestJobs();
		}

		@Override
		public Calls calls() {
			return null;
		}

		@Override
		public Jobs jobs() {
			return jobs;
		}
	}

	private static class TestJobs implements Jobs {
		private CreateJobResponse createJobResponse;

		private List<JobResource> jobResources = new ArrayList<>();

		private List<JobScheduleResource> jobScheduleResources = new ArrayList<>();

		@Override
		public Mono<CreateJobResponse> create(CreateJobRequest request) {
			this.createJobResponse = CreateJobResponse.builder()
					.applicationId(request.getApplicationId())
					.command(request.getCommand())
					.name(request.getName())
					.id("test-job-id-1")
					.build();
			return Mono.just(createJobResponse);
		}

		@Override
		public Mono<Void> delete(DeleteJobRequest request) {
			for (int i = 0; i < this.jobResources.size(); i++) {
				if (this.jobResources.get(i).getId().equals(request.getJobId())) {
					jobResources.remove(i);
					break;
				}
			}
			return Mono.justOrEmpty(null);
		}

		@Override
		public Mono<Void> deleteSchedule(DeleteJobScheduleRequest request) {
			return null;
		}

		@Override
		public Mono<ExecuteJobResponse> execute(ExecuteJobRequest request) {
			return null;
		}

		@Override
		public Mono<GetJobResponse> get(GetJobRequest request) {
			return null;
		}

		@Override
		public Mono<ListJobsResponse> list(ListJobsRequest request) {
			ListJobsResponse response = ListJobsResponse.builder()
					.addAllResources(jobResources)
					.build();
			return Mono.just(response);
		}

		@Override
		public Mono<ListJobHistoriesResponse> listHistories(ListJobHistoriesRequest request) {
			return null;
		}

		@Override
		public Mono<ListJobScheduleHistoriesResponse> listScheduleHistories(ListJobScheduleHistoriesRequest request) {
			return null;
		}

		@Override
		public Mono<ListJobSchedulesResponse> listSchedules(ListJobSchedulesRequest request) {

			ListJobSchedulesResponse response = ListJobSchedulesResponse.builder()
					.addAllResources(jobScheduleResources)
					.build();
			return Mono.just(response);
		}

		@Override
		public Mono<ScheduleJobResponse> schedule(ScheduleJobRequest request) {
			return Mono.just(ScheduleJobResponse.builder().expression(request.getExpression())
					.expressionType(request.getExpressionType())
					.enabled(true)
					.jobId(request.getJobId())
					.id("schedule-1234")
					.build());
		}

	}

	private Flux<SpaceSummary> getTestSpaces() {
		return Flux.just(SpaceSummary.builder().id("test-space-1")
				.name("test-space")
				.build());
	}

	private void mockAppResultsInAppList() {
		givenRequestListApplications(Flux.just(ApplicationSummary.builder()
						.diskQuota(0)
						.id("test-application-id-1")
						.instances(1)
						.memoryLimit(0)
						.name("test-application-1")
						.requestedState("RUNNING")
						.runningInstances(1)
						.build(),
				ApplicationSummary.builder()
						.diskQuota(0)
						.id("test-application-id-2")
						.instances(1)
						.memoryLimit(0)
						.name("test-application-2")
						.requestedState("RUNNING")
						.runningInstances(1)
						.build()));
	}

	private void mockSingleJobInJobList() {
		TestJobs localJobs = (TestJobs) client.jobs();
		localJobs.jobResources.add(JobResource.builder().applicationId("test-application-id-1")
				.command("test-command")
				.id("test-job-1")
				.name("test-job-name-1")
				.build());
		localJobs.jobResources.add(JobResource.builder().applicationId("test-application-id-2")
				.command("test-command")
				.id("test-job-2")
				.name("test-job-name-2")
				.build());
	}

	private void mockSingleSchedule() {
		TestJobs localJobs = (TestJobs) client.jobs();
		localJobs.jobScheduleResources.add(JobScheduleResource.builder()
				.enabled(true)
				.expression(DEFAULT_CRON_EXPRESSION)
				.expressionType(ExpressionType.CRON)
				.id("test-schedule-1")
				.jobId("test-job-1")
				.build());
	}

	private void verifyScheduleInfo(ScheduleInfo scheduleInfo, String taskDefinitionName, String scheduleName, String expression) {
		assertThat(scheduleInfo.getTaskDefinitionName()).isEqualTo(taskDefinitionName);
		assertThat(scheduleInfo.getScheduleName()).isEqualTo(scheduleName);
		if(expression != null) {
			assertThat(scheduleInfo.getScheduleProperties().size()).isEqualTo(1);
			assertThat(scheduleInfo.getScheduleProperties().get(SchedulerPropertyKeys.CRON_EXPRESSION)).isEqualTo(expression);
		} else {
			assertThat(scheduleInfo.getScheduleProperties().size()).isEqualTo(0);
		}
	}
}
