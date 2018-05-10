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

import javax.validation.constraints.NotNull;

import org.springframework.validation.annotation.Validated;

/**
 * Holds configuration properties for connecting to a Cloud Foundry Scheduler.
 *
 * @author Glenn Renfro
 */
@Validated
public class CloudFoundrySchedulerProperties {

	/**
	 * Top level prefix for Cloud Foundry related configuration properties.
	 */
	public static final String CLOUDFOUNDRY_PROPERTIES = "spring.cloud.scheduler.cloudfoundry";

	/**
	 * Location of the PCF scheduler REST API enpoint ot use.
	 */
	@NotNull
	private String schedulerUrl;


	public String getSchedulerUrl() {
		return schedulerUrl;
	}

	public void setSchedulerUrl(String schedulerUrl) {
		this.schedulerUrl = schedulerUrl;
	}

}
