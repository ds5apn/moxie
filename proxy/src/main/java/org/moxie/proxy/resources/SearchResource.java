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
package org.moxie.proxy.resources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.moxie.proxy.Constants;
import org.moxie.proxy.SearchResult;
import org.moxie.utils.StringUtils;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;

public class SearchResource extends BaseResource {

	@Override
	protected String getBasePath() {
		return "search";
	}

	@Override
	protected String getBasePathName() {
		return getTranslation().getString("mp.search");
	}

	@Get
	public Representation toText() {
		String query = getQueryValue("query", "");
		int page = Math.max(1, getQueryValue("page", 1));
		int count = getQueryValue("count", getProxyConfig().getSearchCount());

		List<SearchResult> results;
		if (StringUtils.isEmpty(query)) {
			results = new ArrayList<SearchResult>();
		} else {
			if (query.indexOf(":") == -1 && query.indexOf(' ') == -1) {
				query = "artifactid:*" + query + "* OR groupid:*" + query + "*";
			}
			results = getApplication().search(query, page, count);
		}
		
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("title", Constants.getName());
		map.put("query", query);
		map.put("results", results);
		map.put("pageSize", count);
		map.put("prevPage", page - 1);
		map.put("nextPage", page + 1);
		return toHtml(map, "search.html");
	}
}
