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

package io.edap.http.server.pathrouters;

import io.edap.http.PathInfo;
import io.edap.http.server.PathRouter;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PostfixWildcardPathRouter implements PathRouter {

	private PathInfo[] postfixPathInfos;
	private Lock       lock             = new ReentrantLock();

	public PostfixWildcardPathRouter() {
		postfixPathInfos = new PathInfo[0];
	}

	@Override
	public PathInfo route(String path) {
		int size = postfixPathInfos.length;
		PathInfo pathInfo;
		for (int i=0;i<size;i++) {
			pathInfo  = postfixPathInfos[i];
			if (path.startsWith(pathInfo.getMatchPath())) {
				return pathInfo;
			}
		}
		return null;
	}

	@Override
	public void registerPathInfo(PathInfo pathInfo) {
		lock.lock();
		try {
			if (pathInfo == null || StringUtil.isEmpty(pathInfo.getPath())) {
				return;
			}
			String path = pathInfo.getPath();
			if (!path.endsWith("*")) {
				return;
			}
			String matchPath = path.substring(0, path.length() - 1);
			pathInfo.setMatchPath(matchPath);
			List<PathInfo> pathInfos = new ArrayList<>();
			int size = postfixPathInfos.length;
			if (size > 0) {
				for (int i = 0; i < size; i++) {
					PathInfo info = postfixPathInfos[i];
					pathInfos.add(info);
				}
			}
			if (!pathInfos.contains(pathInfo)) {
				pathInfos.add(pathInfo);
				Collections.sort(pathInfos, (o1, o2) -> {
					if (o1.getPath().length() > o2.getPath().length()) {
						return -1;
					} else if (o1.getPath().length() < o2.getPath().length()) {
						return 1;
					} else {
						return o1.getPath().compareTo(o2.getPath());
					}
				});
				postfixPathInfos = pathInfos.toArray(new PathInfo[0]);
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void unregisterPathInfo(PathInfo pathInfo) {
		lock.lock();
		try {
			if (pathInfo == null || StringUtil.isEmpty(pathInfo.getPath())) {
				return;
			}
			String path = pathInfo.getPath();
			if (!path.endsWith("*")) {
				return;
			}
			int size = postfixPathInfos.length;
			if (size == 0) {
				return;
			}
			List<PathInfo> pathInfos = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				PathInfo info = postfixPathInfos[i];
				if (!info.getPath().equals(pathInfo.getPath())) {
					pathInfos.add(info);
				}
			}
			postfixPathInfos = pathInfos.toArray(new PathInfo[0]);
		} finally {
			lock.unlock();
		}
	}
}
