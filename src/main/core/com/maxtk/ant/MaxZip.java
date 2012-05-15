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

import java.io.File;

import org.apache.tools.ant.taskdefs.Zip;

import com.maxtk.Build;
import com.maxtk.Constants.Key;
import com.maxtk.utils.StringUtils;

public class MaxZip extends Zip {
	
	public void execute() {
		Build build = (Build) getProject().getReference(Key.build.maxId());
		if (zipFile == null) {
			// default output jar if file unspecified
			String name = getProject().getProperty(Key.artifactId.maxId());
			if (!StringUtils.isEmpty(getProject().getProperty(Key.version.maxId()))) {
				name += "-" + getProject().getProperty(Key.version.maxId());
			}
			zipFile = new File(build.getTargetFolder(), name + ".zip");
		}
		
		if (zipFile.getParentFile() != null) {
			zipFile.getParentFile().mkdirs();
		}
		super.execute();
	}
}