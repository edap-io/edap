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

package io.edap.http.server.test.pathrouter;

import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.server.handler.NotFoundHandler;
import io.edap.http.server.pathrouters.PrefixWildcardPathRouter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class PrefixWildcardPathRouterTest {

	@Test
	public void testRoute() {
		PrefixWildcardPathRouter router = new PrefixWildcardPathRouter();
		PathInfo pathInfo1 = new PathInfo();
		pathInfo1.setPath("*.jsp");
		pathInfo1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo2 = new PathInfo();
		pathInfo2.setPath("*.jspx");
		pathInfo2.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3 = new PathInfo();
		pathInfo3.setPath("*.do");
		pathInfo3.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo4 = new PathInfo();
		pathInfo4.setPath("*.action");
		pathInfo4.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo5 = new PathInfo();
		pathInfo5.setPath("*.html");
		pathInfo5.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		router.registerPathInfo(pathInfo3);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo1);
		router.registerPathInfo(pathInfo4);
		router.registerPathInfo(pathInfo5);

		PathInfo pathInfo = router.route("/index.jspx");
		assertNotNull(pathInfo);
		assertEquals(pathInfo.getPath(), "*.jspx");

		pathInfo = router.route("/index.jsps");
		assertNull(pathInfo);
	}

	@Test
	public void testRegisterPathInfo() throws NoSuchFieldException, IllegalAccessException {
		PrefixWildcardPathRouter router = new PrefixWildcardPathRouter();
		router.registerPathInfo(null);
		router.registerPathInfo(new PathInfo());
		PathInfo pi1 = new PathInfo();
		pi1.setPath("/index.html");
		router.registerPathInfo(pi1);

		PathInfo pathInfo1 = new PathInfo();
		pathInfo1.setPath("*.jsp");
		pathInfo1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo2 = new PathInfo();
		pathInfo2.setPath("*.jspx");
		pathInfo2.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3 = new PathInfo();
		pathInfo3.setPath("*.do");
		pathInfo3.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo4 = new PathInfo();
		pathInfo4.setPath("*.action");
		pathInfo4.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo5 = new PathInfo();
		pathInfo5.setPath("*.html");
		pathInfo5.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		router.registerPathInfo(pathInfo3);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo1);
		router.registerPathInfo(pathInfo4);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo5);

		Field infosField = PrefixWildcardPathRouter.class.getDeclaredField("prefixPathInfos");
		infosField.setAccessible(true);
		PathInfo[] pathInfos = (PathInfo[])infosField.get(router);
		assertNotNull(pathInfos);
		assertEquals(pathInfos.length, 5);
		assertEquals(pathInfos[0].getPath(), "*.action");
		assertEquals(pathInfos[0].getMatchPath(), ".action");
		assertEquals(pathInfos[1].getPath(), "*.html");
		assertEquals(pathInfos[1].getMatchPath(), ".html");
		assertEquals(pathInfos[2].getPath(), "*.jspx");
		assertEquals(pathInfos[2].getMatchPath(), ".jspx");
		assertEquals(pathInfos[3].getPath(), "*.jsp");
		assertEquals(pathInfos[3].getMatchPath(), ".jsp");
		assertEquals(pathInfos[4].getPath(), "*.do");
		assertEquals(pathInfos[4].getMatchPath(), ".do");
	}

	@Test
	public void testUnregisterPathInfo() throws NoSuchFieldException, IllegalAccessException {
		PrefixWildcardPathRouter router = new PrefixWildcardPathRouter();

		PathInfo pi = new PathInfo();
		pi.setPath("index.do");
		router.unregisterPathInfo(pi);

		PathInfo pi2 = new PathInfo();
		pi2.setPath("*.php");
		router.unregisterPathInfo(pi2);

		router.unregisterPathInfo(null);
		router.unregisterPathInfo(new PathInfo());

		router.unregisterPathInfo(pi);

		PathInfo pathInfo1 = new PathInfo();
		pathInfo1.setPath("*.jsp");
		pathInfo1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo2 = new PathInfo();
		pathInfo2.setPath("*.jspx");
		pathInfo2.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3 = new PathInfo();
		pathInfo3.setPath("*.do");
		pathInfo3.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo4 = new PathInfo();
		pathInfo4.setPath("*.action");
		pathInfo4.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo5 = new PathInfo();
		pathInfo5.setPath("*.html");
		pathInfo5.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		router.registerPathInfo(pathInfo3);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo1);
		router.registerPathInfo(pathInfo4);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo5);

		Field infosField = PrefixWildcardPathRouter.class.getDeclaredField("prefixPathInfos");
		infosField.setAccessible(true);
		PathInfo[] pathInfos = (PathInfo[])infosField.get(router);
		assertNotNull(pathInfos);
		assertEquals(pathInfos.length, 5);
		router.unregisterPathInfo(pathInfo2);
		pathInfos = (PathInfo[])infosField.get(router);
		assertEquals(pathInfos.length, 4);
		assertEquals(pathInfos[0].getPath(), "*.action");
		assertEquals(pathInfos[0].getMatchPath(), ".action");
		assertEquals(pathInfos[1].getPath(), "*.html");
		assertEquals(pathInfos[1].getMatchPath(), ".html");
		assertEquals(pathInfos[2].getPath(), "*.jsp");
		assertEquals(pathInfos[2].getMatchPath(), ".jsp");
		assertEquals(pathInfos[3].getPath(), "*.do");
		assertEquals(pathInfos[3].getMatchPath(), ".do");

	}
}
