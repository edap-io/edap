package io.edap.common.test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class T {

    public static void main(String[] args) {
       String s = "/*\n" +
               " * Copyright 2023 The edap Project\n" +
               " *\n" +
               " *    Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
               " *    you may not use this file except in compliance with the License.\n" +
               " *    You may obtain a copy of the License at\n" +
               " *\n" +
               " *      http://www.apache.org/licenses/LICENSE-2.0\n" +
               " *\n" +
               " *    Unless required by applicable law or agreed to in writing, software\n" +
               " *    distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
               " *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
               " *    See the License for the specific language governing permissions and\n" +
               " *    limitations under the License.\n" +
               " */\n" +
               "\n" +
               "package io.edap.nio.handler;\n" +
               "\n" +
               "import com.lmax.disruptor.EventHandler;\n" +
               "import io.edap.NioServerSession;\n" +
               "import io.edap.ParseResult;\n" +
               "import io.edap.Server;\n" +
               "import io.edap.log.Logger;\n" +
               "import io.edap.log.LoggerManager;\n" +
               "import io.edap.nio.event.BizEvent;\n" +
               "import io.edap.util.CollectionUtils;\n" +
               "\n" +
               "import java.util.List;\n" +
               "\n" +
               "public class BizEventHandler implements EventHandler<BizEvent>  {\n" +
               "\n" +
               "    Logger LOG = LoggerManager.getLogger(BizEventHandler.class);\n" +
               "\n" +
               "    public BizEventHandler(Server server) {\n" +
               "\n" +
               "    }\n" +
               "\n" +
               "    @Override\n" +
               "    public void onEvent(BizEvent event, long sequence, boolean endOfBatch) throws Exception {\n" +
               "        ParseResult pr = event.getBizData();\n" +
               "        LOG.trace(\"event bizData: {}\", l -> l.arg(pr.getMessages().size()));\n" +
               "        List<Object> objs = pr.getMessages();\n" +
               "        if (!CollectionUtils.isEmpty(objs)) {\n" +
               "            NioServerSession nioSession = event.getNioSession();\n" +
               "            for (int i=0;i<objs.size();i++) {\n" +
               "                nioSession.handle(objs.get(i));\n" +
               "            }\n" +
               "        }\n" +
               "    }\n" +
               "}";

       System.out.println(s.getBytes().length);
    }
}
