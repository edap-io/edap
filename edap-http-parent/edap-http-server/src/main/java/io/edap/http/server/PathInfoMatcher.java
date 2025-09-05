/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.http.server;

import io.edap.http.PathInfo;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.cache.PathCache;
import io.edap.http.server.pathrouters.PostfixWildcardPathRouter;
import io.edap.http.server.pathrouters.PrefixWildcardPathRouter;
import io.edap.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PathInfoMatcher {

	static PathCache        CACHE   = PathCache.instance();
	static List<PathRouter> ROUTERS = new CopyOnWriteArrayList<>();

	private PathInfoMatcher() {
		if (!CollectionUtils.isEmpty(ROUTERS)) {
			int count = ROUTERS.size();
			for (int i = count-1;i>=0;i--) {
				PathRouter r = ROUTERS.get(i);
				if (r.getClass().getName().equals(PostfixWildcardPathRouter.class.getName())) {
					ROUTERS.remove(r);
				} else if (r.getClass().getName().equals(PrefixWildcardPathRouter.class.getName())) {
					ROUTERS.remove(r);
				}
			}
		}
		PostfixWildcardPathRouter postfixRouter = new PostfixWildcardPathRouter();
		PrefixWildcardPathRouter  prefixRoute   = new PrefixWildcardPathRouter();
		ROUTERS.add(prefixRoute);
		ROUTERS.add(postfixRouter);
	}

	public PathInfo match(HttpFastBufDataRange dataRange) {
		PathInfo pathInfo = CACHE.get(dataRange);
		if (pathInfo != null) {
			return pathInfo;
		}
		int size = ROUTERS.size();
		PathInfo pi;
		if (size == 0) {
			return null;
		}
		String path = dataRange.getString(StandardCharsets.UTF_8);
		for (int i=0;i<size;i++) {
			PathRouter pr = ROUTERS.get(i);
			pi = pr.route(path);
			if (pi != null) {
				return pi;
			}
		}
		return null;
	}

	public static final PathInfoMatcher instance() {
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder {
		private static final PathInfoMatcher INSTANCE = new PathInfoMatcher();
	}
}
