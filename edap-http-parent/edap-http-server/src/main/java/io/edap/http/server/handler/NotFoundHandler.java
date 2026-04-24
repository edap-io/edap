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

package io.edap.http.server.handler;


import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.header.ContentTypeHeader;

import java.nio.charset.StandardCharsets;

public class NotFoundHandler implements HttpHandler {

    private static byte[] NOT_FOUND_CONTENT;

    @Override
    public void handle(HttpRequest req, HttpResponse resp) {
        if (NOT_FOUND_CONTENT == null) {
            initData();
        }
        resp.contentType(ContentTypeHeader.HTML);
        resp.write(NOT_FOUND_CONTENT);
    }

    private static void initData() {
        NOT_FOUND_CONTENT = loadData("/404.html", (" <!DOCTYPE html>\n" +
                " <html lang=\"zh-CN\">\n" +
                " <head>\n" +
                "   <meta charset=\"UTF-8\">\n" +
                "   <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "   <title>404 - 页面未找到</title>\n" +
                "   <link rel=\"icon\" type=\"image/svg+xml\" href=\"blank.svg\">\n" +
                "   <style>\n" +
                "     * {\n" +
                "       margin: 0;\n" +
                "       padding: 0;\n" +
                "       box-sizing: border-box;\n" +
                "     }\n" +
                "\n" +
                "     body {\n" +
                "       min-height: 100vh;\n" +
                "       background: #f8fafc;\n" +
                "       display: flex;\n" +
                "       align-items: center;\n" +
                "       justify-content: center;\n" +
                "       font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;\n" +
                "       color: #1e293b;\n" +
                "       overflow: hidden;\n" +
                "       position: relative;\n" +
                "     }\n" +
                "\n" +
                "     /* 背景光晕 */\n" +
                "     .glow {\n" +
                "       position: fixed;\n" +
                "       border-radius: 50%;\n" +
                "       filter: blur(100px);\n" +
                "       opacity: 0.45;\n" +
                "       z-index: 0;\n" +
                "       pointer-events: none;\n" +
                "     }\n" +
                "\n" +
                "     .glow-1 {\n" +
                "       width: 500px;\n" +
                "       height: 500px;\n" +
                "       background: #e0e7ff;\n" +
                "       top: -150px;\n" +
                "       left: -150px;\n" +
                "       animation: drift1 14s ease-in-out infinite alternate;\n" +
                "     }\n" +
                "\n" +
                "     .glow-2 {\n" +
                "       width: 400px;\n" +
                "       height: 400px;\n" +
                "       background: #fce7f3;\n" +
                "       bottom: -150px;\n" +
                "       right: -150px;\n" +
                "       animation: drift2 16s ease-in-out infinite alternate;\n" +
                "     }\n" +
                "\n" +
                "     @keyframes drift1 { to { transform: translate(60px, 60px); } }\n" +
                "     @keyframes drift2 { to { transform: translate(-50px, -50px); } }\n" +
                "\n" +
                "     /* 主内容 */\n" +
                "     .container {\n" +
                "       position: relative;\n" +
                "       z-index: 1;\n" +
                "       text-align: center;\n" +
                "       padding: 40px 20px;\n" +
                "       max-width: 560px;\n" +
                "     }\n" +
                "\n" +
                "     /* 飘浮图标 */\n" +
                "     .ghost {\n" +
                "       font-size: 5rem;\n" +
                "       margin-bottom: 16px;\n" +
                "       opacity: 0.6;\n" +
                "       animation: float 3s ease-in-out infinite;\n" +
                "     }\n" +
                "\n" +
                "     @keyframes float {\n" +
                "       0%, 100% { transform: translateY(0); }\n" +
                "       50% { transform: translateY(-12px); }\n" +
                "     }\n" +
                "\n" +
                "     /* 404 数字 */\n" +
                "     .number {\n" +
                "       font-size: 9rem;\n" +
                "       font-weight: 900;\n" +
                "       letter-spacing: -0.06em;\n" +
                "       line-height: 1;\n" +
                "       background: linear-gradient(135deg, #c7d2fe 0%, #818cf8 40%, #a78bfa 70%, #c4b5fd 100%);\n" +
                "       -webkit-background-clip: text;\n" +
                "       -webkit-text-fill-color: transparent;\n" +
                "       background-clip: text;\n" +
                "       margin-bottom: 8px;\n" +
                "     }\n" +
                "\n" +
                "     /* 标题 */\n" +
                "     h1 {\n" +
                "       font-size: 1.5rem;\n" +
                "       font-weight: 700;\n" +
                "       color: #1e293b;\n" +
                "       margin-bottom: 12px;\n" +
                "     }\n" +
                "\n" +
                "     /* 描述 */\n" +
                "     p {\n" +
                "       font-size: 1rem;\n" +
                "       color: #64748b;\n" +
                "       line-height: 1.7;\n" +
                "       margin-bottom: 40px;\n" +
                "     }\n" +
                "\n" +
                "     /* 按钮组 */\n" +
                "     .actions {\n" +
                "       display: flex;\n" +
                "       gap: 12px;\n" +
                "       justify-content: center;\n" +
                "       flex-wrap: wrap;\n" +
                "     }\n" +
                "\n" +
                "     .btn {\n" +
                "       display: inline-flex;\n" +
                "       align-items: center;\n" +
                "       gap: 6px;\n" +
                "       padding: 12px 24px;\n" +
                "       border-radius: 10px;\n" +
                "       font-size: 0.9375rem;\n" +
                "       font-weight: 600;\n" +
                "       text-decoration: none;\n" +
                "       cursor: pointer;\n" +
                "       border: none;\n" +
                "       transition: transform 0.15s, box-shadow 0.15s;\n" +
                "     }\n" +
                "\n" +
                "     .btn:hover {\n" +
                "       transform: translateY(-1px);\n" +
                "     }\n" +
                "\n" +
                "     .btn:active {\n" +
                "       transform: translateY(0);\n" +
                "     }\n" +
                "\n" +
                "     .btn-primary {\n" +
                "       background: linear-gradient(135deg, #6366f1, #8b5cf6);\n" +
                "       color: #fff;\n" +
                "       box-shadow: 0 4px 14px rgba(99, 102, 241, 0.3);\n" +
                "     }\n" +
                "\n" +
                "     .btn-primary:hover {\n" +
                "       box-shadow: 0 6px 20px rgba(99, 102, 241, 0.4);\n" +
                "     }\n" +
                "\n" +
                "     .btn-secondary {\n" +
                "       background: #fff;\n" +
                "       color: #475569;\n" +
                "       border: 1.5px solid #e2e8f0;\n" +
                "     }\n" +
                "\n" +
                "     .btn-secondary:hover {\n" +
                "       border-color: #c7d2fe;\n" +
                "       color: #4338ca;\n" +
                "       box-shadow: 0 4px 14px rgba(99, 102, 241, 0.08);\n" +
                "     }\n" +
                "\n" +
                "     /* 分割线 */\n" +
                "     .divider {\n" +
                "       display: flex;\n" +
                "       align-items: center;\n" +
                "       gap: 12px;\n" +
                "       margin: 32px 0;\n" +
                "       color: #cbd5e1;\n" +
                "       font-size: 0.8125rem;\n" +
                "     }\n" +
                "\n" +
                "     .divider::before,\n" +
                "     .divider::after {\n" +
                "       content: '';\n" +
                "       flex: 1;\n" +
                "       height: 1px;\n" +
                "       background: #e2e8f0;\n" +
                "     }\n" +
                "\n" +
                "     /* 搜索框 */\n" +
                "     .search-box {\n" +
                "       display: flex;\n" +
                "       max-width: 360px;\n" +
                "       margin: 0 auto;\n" +
                "       background: #fff;\n" +
                "       border: 1.5px solid #e2e8f0;\n" +
                "       border-radius: 10px;\n" +
                "       overflow: hidden;\n" +
                "       transition: border-color 0.2s, box-shadow 0.2s;\n" +
                "     }\n" +
                "\n" +
                "     .search-box:focus-within {\n" +
                "       border-color: #6366f1;\n" +
                "       box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1);\n" +
                "     }\n" +
                "\n" +
                "     .search-box input {\n" +
                "       flex: 1;\n" +
                "       padding: 12px 16px;\n" +
                "       border: none;\n" +
                "       outline: none;\n" +
                "       font-size: 0.9375rem;\n" +
                "       color: #1e293b;\n" +
                "       background: transparent;\n" +
                "     }\n" +
                "\n" +
                "     .search-box input::placeholder {\n" +
                "       color: #94a3b8;\n" +
                "     }\n" +
                "\n" +
                "     .search-box button {\n" +
                "       padding: 12px 16px;\n" +
                "       background: transparent;\n" +
                "       border: none;\n" +
                "       cursor: pointer;\n" +
                "       color: #6366f1;\n" +
                "       font-size: 1.1rem;\n" +
                "       display: flex;\n" +
                "       align-items: center;\n" +
                "     }\n" +
                "\n" +
                "     /* 快速链接 */\n" +
                "     .quick-links {\n" +
                "       display: flex;\n" +
                "       justify-content: center;\n" +
                "       gap: 24px;\n" +
                "       flex-wrap: wrap;\n" +
                "     }\n" +
                "\n" +
                "     .quick-links a {\n" +
                "       font-size: 0.875rem;\n" +
                "       color: #6366f1;\n" +
                "       text-decoration: none;\n" +
                "       transition: color 0.2s;\n" +
                "     }\n" +
                "\n" +
                "     .quick-links a:hover {\n" +
                "       color: #4338ca;\n" +
                "     }\n" +
                "\n" +
                "     /* 响应式 */\n" +
                "     @media (max-width: 480px) {\n" +
                "       .number { font-size: 6rem; }\n" +
                "       .actions { flex-direction: column; }\n" +
                "       .actions .btn { width: 100%; justify-content: center; }\n" +
                "     }\n" +
                "   </style>\n" +
                " </head>\n" +
                " <body>\n" +
                "\n" +
                "   <!-- 背景光晕 -->\n" +
                "   <div class=\"glow glow-1\"></div>\n" +
                "   <div class=\"glow glow-2\"></div>\n" +
                "\n" +
                "   <!-- 主内容 -->\n" +
                "   <div class=\"container\">\n" +
                "\n" +
                "     <!-- 飘浮图标 -->\n" +
                "     <div class=\"ghost\">\uD83D\uDC7B</div>\n" +
                "\n" +
                "     <!-- 404 数字 -->\n" +
                "     <div class=\"number\">404</div>\n" +
                "\n" +
                "     <!-- 标题 -->\n" +
                "     <h1>页面未找到</h1>\n" +
                "\n" +
                "     <!-- 描述 -->\n" +
                "     <p>\n" +
                "       您访问的页面可能已被删除、重命名<br>\n" +
                "       或暂时不可用\n" +
                "     </p>\n" +
                "\n" +
                "     <!-- 操作按钮 -->\n" +
                "     <div class=\"actions\">\n" +
                "       <a href=\"/\" class=\"btn btn-primary\">\n" +
                "         返回首页\n" +
                "       </a>\n" +
                "       <a href=\"javascript:history.back()\" class=\"btn btn-secondary\">\n" +
                "         返回上页\n" +
                "       </a>\n" +
                "     </div>\n" +
                "\n" +
                "     <!-- 分割线 -->\n" +
                "     <div class=\"divider\">或者</div>\n" +
                "\n" +
                "     <!-- 搜索 -->\n" +
                "     <form class=\"search-box\" onsubmit=\"return false;\">\n" +
                "       <input type=\"text\" placeholder=\"搜索内容...\" autocomplete=\"off\">\n" +
                "       <button type=\"submit\">\uD83D\uDD0D</button>\n" +
                "     </form>\n" +
                "\n" +
                "     <!-- 快速链接 -->\n" +
                "     <div class=\"quick-links\">\n" +
                "       <a href=\"/\">首页</a>\n" +
                "       <a href=\"/about\">关于我们</a>\n" +
                "       <a href=\"/help\">帮助中心</a>\n" +
                "       <a href=\"/contact\">联系我们</a>\n" +
                "     </div>\n" +
                "\n" +
                "   </div>\n" +
                "\n" +
                " </body>\n" +
                " </html>").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] loadData(String name, byte[] defaultData) {
        byte[] data;
        try {
            data = NotFoundHandler.class
                    .getResourceAsStream(name).readAllBytes();
            return data;
        } catch (Throwable e) {
            return defaultData;
        }
    }
}
