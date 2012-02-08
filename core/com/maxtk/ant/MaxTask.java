/*
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.maxtk.ant;

import org.apache.tools.ant.Task;

import com.maxtk.utils.StringUtils;

public abstract class MaxTask extends Task {

	public enum Property {
		max_conf, max_version, max_name, max_description, max_vendor, max_artifactId, max_url, max_outputFolder, max_classpath, max_sourceFolders, max_commit;

		public String id() {
			return name().replace('_', '-');
		}
	}

	protected boolean verbose;

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	protected void setProperty(Property prop, String value) {
		if (!StringUtils.isEmpty(value)) {
			getProject().setProperty(prop.id(), value);
			if (verbose) {
				log(StringUtils.leftPad(prop.id(), 18, ' ') + ": " + value);
			}
		}
	}

	protected void setProperty(String prop, String value) {
		if (!StringUtils.isEmpty(value)) {
			getProject().setProperty(prop, value);
			if (verbose) {
				log(StringUtils.leftPad(prop, 18, ' ') + ": " + value);
			}
		}
	}

	protected void addReference(Property prop, Object obj) {
		getProject().addReference(prop.id(), obj);
		if (verbose) {
			log(StringUtils.leftPad(prop.id(), 18, ' ') + ": " + obj.toString());
		}
	}
}
