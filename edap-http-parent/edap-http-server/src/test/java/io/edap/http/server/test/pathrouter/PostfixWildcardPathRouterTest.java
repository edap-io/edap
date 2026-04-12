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
import io.edap.http.server.pathrouters.PostfixWildcardPathRouter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class PostfixWildcardPathRouterTest {

	@Test
	public void testRoute() {
		PostfixWildcardPathRouter router = new PostfixWildcardPathRouter();
		PathInfo pathInfo1 = new PathInfo();
		pathInfo1.setPath("/my/*");
		pathInfo1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo2 = new PathInfo();
		pathInfo2.setPath("/user/*");
		pathInfo2.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3 = new PathInfo();
		pathInfo3.setPath("/order/*");
		pathInfo3.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3_1 = new PathInfo();
		pathInfo3_1.setPath("/order/check/*");
		pathInfo3_1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo4 = new PathInfo();
		pathInfo4.setPath("/stock/*");
		pathInfo4.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo = router.route("/order/list.do");
		assertNull(pathInfo);

		router.registerPathInfo(pathInfo1);
		router.registerPathInfo(pathInfo3);
		router.registerPathInfo(pathInfo3_1);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo4);

		pathInfo = router.route("/order/list.do");
		assertNotNull(pathInfo);
		assertEquals(pathInfo.getPath(), "/order/*");

		pathInfo = router.route("/order/check/my.do");
		assertNotNull(pathInfo);
		assertEquals(pathInfo.getPath(), "/order/check/*");

		pathInfo = router.route("/order1/list.do");
		assertNull(pathInfo);
	}

	@Test
	public void testRegisterPathInfo() throws NoSuchFieldException, IllegalAccessException {
		PostfixWildcardPathRouter router = new PostfixWildcardPathRouter();
		PathInfo pathInfo1 = new PathInfo();
		pathInfo1.setPath("/*");
		pathInfo1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo2 = new PathInfo();
		pathInfo2.setPath("/user/*");
		pathInfo2.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3 = new PathInfo();
		pathInfo3.setPath("/order/*");
		pathInfo3.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3_1 = new PathInfo();
		pathInfo3_1.setPath("/order/check/*");
		pathInfo3_1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo4 = new PathInfo();
		pathInfo4.setPath("/stock/*");
		pathInfo4.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		router.registerPathInfo(pathInfo1);
		router.registerPathInfo(pathInfo3);
		router.registerPathInfo(pathInfo3_1);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo4);
		router.registerPathInfo(pathInfo4);

		router.registerPathInfo(null);
		PathInfo pathInfo5 = new PathInfo();
		router.registerPathInfo(pathInfo5);

		PathInfo pathInfo6 = new PathInfo();
		pathInfo6.setPath("/stock/");
		pathInfo6.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});
		router.registerPathInfo(pathInfo6);

		Field infosField = PostfixWildcardPathRouter.class.getDeclaredField("postfixPathInfos");
		infosField.setAccessible(true);
		PathInfo[] pathInfos = (PathInfo[])infosField.get(router);
		assertNotNull(pathInfos);
		assertEquals(pathInfos.length, 5);
		assertEquals(pathInfos[0].getPath(), "/order/check/*");
		assertEquals(pathInfos[0].getMatchPath(), "/order/check/");
		assertEquals(pathInfos[1].getPath(), "/order/*");
		assertEquals(pathInfos[1].getMatchPath(), "/order/");
		assertEquals(pathInfos[2].getPath(), "/stock/*");
		assertEquals(pathInfos[2].getMatchPath(), "/stock/");
		assertEquals(pathInfos[3].getPath(), "/user/*");
		assertEquals(pathInfos[3].getMatchPath(), "/user/");
		assertEquals(pathInfos[4].getPath(), "/*");
		assertEquals(pathInfos[4].getMatchPath(), "/");
	}

	@Test
	public void testUnregisterPathInfo() throws NoSuchFieldException, IllegalAccessException {

		PostfixWildcardPathRouter router = new PostfixWildcardPathRouter();
		router.unregisterPathInfo(null);
		router.unregisterPathInfo(new PathInfo());
		PathInfo pi = new PathInfo();
		pi.setPath("/login");
		router.unregisterPathInfo(pi);

		PathInfo pi2 = new PathInfo();
		pi2.setPath("/picture/*");
		router.unregisterPathInfo(pi2);

		PathInfo pathInfo1 = new PathInfo();
		pathInfo1.setPath("/*");
		pathInfo1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo2 = new PathInfo();
		pathInfo2.setPath("/user/*");
		pathInfo2.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3 = new PathInfo();
		pathInfo3.setPath("/order/*");
		pathInfo3.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo3_1 = new PathInfo();
		pathInfo3_1.setPath("/order/check/*");
		pathInfo3_1.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		PathInfo pathInfo4 = new PathInfo();
		pathInfo4.setPath("/stock/*");
		pathInfo4.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});

		router.registerPathInfo(pathInfo1);
		router.registerPathInfo(pathInfo3);
		router.registerPathInfo(pathInfo3_1);
		router.registerPathInfo(pathInfo2);
		router.registerPathInfo(pathInfo4);

		Field infosField = PostfixWildcardPathRouter.class.getDeclaredField("postfixPathInfos");
		infosField.setAccessible(true);
		PathInfo[] pathInfos = (PathInfo[])infosField.get(router);
		assertEquals(pathInfos.length, 5);
		assertEquals(pathInfos[0].getPath(), "/order/check/*");
		assertEquals(pathInfos[0].getMatchPath(), "/order/check/");
		assertEquals(pathInfos[1].getPath(), "/order/*");
		assertEquals(pathInfos[1].getMatchPath(), "/order/");
		assertEquals(pathInfos[2].getPath(), "/stock/*");
		assertEquals(pathInfos[2].getMatchPath(), "/stock/");
		assertEquals(pathInfos[3].getPath(), "/user/*");
		assertEquals(pathInfos[3].getMatchPath(), "/user/");
		assertEquals(pathInfos[4].getPath(), "/*");
		assertEquals(pathInfos[4].getMatchPath(), "/");

		router.unregisterPathInfo(pathInfo3);
		pathInfos = (PathInfo[])infosField.get(router);
		assertEquals(pathInfos.length, 4);
		assertEquals(pathInfos[0].getPath(), "/order/check/*");
		assertEquals(pathInfos[0].getMatchPath(), "/order/check/");
		assertEquals(pathInfos[1].getPath(), "/stock/*");
		assertEquals(pathInfos[1].getMatchPath(), "/stock/");
		assertEquals(pathInfos[2].getPath(), "/user/*");
		assertEquals(pathInfos[2].getMatchPath(), "/user/");
		assertEquals(pathInfos[3].getPath(), "/*");
		assertEquals(pathInfos[3].getMatchPath(), "/");
	}
}
