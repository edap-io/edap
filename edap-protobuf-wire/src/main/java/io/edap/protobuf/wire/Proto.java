/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf.wire;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * proto文件的结构定义
 */
public class Proto {

    private boolean empty;
    /**
     * Proto描述文件的名称
     */
    private String name;
    /**
     * Proto的可选信息
     */
    private List<Option> options;
    /**
     * 文档版本没有指定默认为proto2
     */
    private Syntax syntax;
    /**
     * proto文件的包名
     */
    private String protoPackage;
    /**
     * 定义的消息列表
     */
    private List<Message> messages;
    /**
     * 文档导入的文件的路径列表
     */
    private List<String> imports;
    /**
     * 文档的注释
     */
    private String comment;
    /**
     * 消息内置的枚举类型
     */
    private List<ProtoEnum> enums;

    /**
     * 消息内嵌的扩展的消息列表
     */
    private List<Extend> protoExtends;
    /**
     * proto文件定义的service的列表
     */
    private List<Service> services;
    private Map<String, Message> messageMap;
    /**
     * proto文件的单行注释列表
     */
    private List<String> comments;
    /**
     * 该文件的来源的File对象
     */
    private File file;

    public Proto() {
        this.empty = true;
    }


    public Proto setEnums(List<ProtoEnum> enums) {
        if (enums == null) {
            return this;
        }
        empty = false;
        if (enums instanceof ArrayList) {
            this.enums = enums;
        } else {
            getEnums().addAll(enums);
        }
        return this;
    }

    public Proto setImports(List<String> imports) {
        if (imports == null) {
            return this;
        }
        if (imports instanceof ArrayList) {
            this.imports = imports;
        } else {
            List<String> list = getImports();
            list.addAll(imports);
        }
        empty = false;
        return this;
    }

    public Proto setMessages(List<Message> messages) {
        if (messages == null) {
            return this;
        }
        if (messages instanceof ArrayList) {
            this.messages = messages;
        } else {
            List<Message> msgs = getMessages();
            msgs.addAll(messages);
        }
        empty = false;
        return this;
    }

    public Proto setOptions(List<Option> options) {
        if (options == null) {
            return this;
        }
        this.empty = false;
        if (options instanceof ArrayList) {
            this.options = options;
        } else {
            List<Option> list = getOptions();
            list.addAll(options);
        }
        return this;
    }

    public Proto setProtoExtends(List<Extend> protoExtends) {
        if (protoExtends == null) {
            return this;
        }
        if (protoExtends instanceof ArrayList) {
            this.protoExtends = protoExtends;
        } else {
            List<Extend> exts = getProtoExtends();
            exts.addAll(protoExtends);
        }
        this.empty = false;
        return this;
    }

    public Proto setSyntax(Syntax syntax) {
        if (isEmpty()) {
            this.syntax = syntax;
            this.empty = false;
        }
        return this;
    }

    public Proto addOption(Option option) {
        empty = false;
        getOptions().add(option);
        return this;
    }

    public Proto addImport(String value) {
        empty = false;
        getImports().add(value);
        return this;
    }

    public Proto addMsg(Message msg) {
        empty = false;
        getMessages().add(msg);
        return this;
    }

    public Proto addEnum(ProtoEnum protoEnum) {
        empty = false;
        getEnums().add(protoEnum);
        return this;
    }

    public Proto addService(Service service) {
        if (service == null) {
            return this;
        }
        empty = false;
        getServices().add(service);
        return this;
    }

    public Proto addExtend(Extend extend) {
        if (extend == null) {
            return this;
        }
        empty = false;
        getProtoExtends().add(extend);
        return this;
    }

    /**
     * Proto描述文件的名称
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Proto描述文件的名称
     * @param name the name to set
     * @return
     */
    public Proto setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Proto的可选信息
     * @return the options
     */
    public List<Option> getOptions() {
        if (options == null) {
            options = new ArrayList<>();
        }
        return options;
    }

    /**
     * 文档版本没有指定默认为proto2
     * @return the syntax
     */
    public Syntax getSyntax() {

        return syntax == null ? Syntax.PROTO_2 : syntax;
    }

    /**
     * proto文件的包名
     * @return the protoPackage
     */
    public String getProtoPackage() {
        return protoPackage;
    }

    /**
     * proto文件的包名
     * @param protoPackage the protoPackage to set
     * @return
     */
    public Proto setProtoPackage(String protoPackage) {
        this.protoPackage = protoPackage;
        this.empty = false;
        return this;
    }

    /**
     * 定义的消息列表
     * @return the messages
     */
    public List<Message> getMessages() {
        if (messages == null) {
            messages = new ArrayList<>();
        }
        return messages;
    }

    /**
     * 文档导入的文件的路径列表
     * @return the imports
     */
    public List<String> getImports() {
        if (imports == null) {
            imports = new ArrayList<>();
        }
        return imports;
    }

    /**
     * 文档的注释
     * @return the comment
     */
    public String getComment() {
        return comment;
    }

    /**
     * 文档的注释
     * @param comment the comment to set
     * @return
     */
    public Proto setComment(String comment) {
        this.comment = comment;
        return this;
    }

    /**
     * 消息内置的枚举类型
     * @return the enums
     */
    public List<ProtoEnum> getEnums() {
        if (enums == null) {
            enums = new ArrayList<>();
        }
        return enums;
    }

    /**
     * 消息内嵌的扩展的消息列表
     * @return the protoExtends
     */
    public List<Extend> getProtoExtends() {
        if (protoExtends == null) {
            protoExtends = new ArrayList<>();
        }
        return protoExtends;
    }

    /**
     * proto文件定义的service的列表
     * @return the services
     */
    public List<Service> getServices() {
        if (services == null) {
            services = new ArrayList<>();
        }
        return services;
    }

    /**
     * proto文件定义的service的列表
     * @param services the services to set
     * @return
     */
    public Proto setServices(List<Service> services) {
        if (services == null) {
            return this;
        }
        if (services instanceof ArrayList) {
            this.services = services;
        } else {
            getServices().addAll(services);
        }
        this.empty = false;
        return this;
    }

    /**
     * proto文件的单行注释列表
     * @return the comments
     */
    public List<String> getComments() {
        if (comments == null) {
            comments = new ArrayList<>();
        }
        return comments;
    }

    /**
     * proto文件的单行注释列表
     * @param comments the comments to set
     * @return
     */
    public Proto setComments(List<String> comments) {
        if (comments == null) {
            return this;
        }
        if (comments instanceof ArrayList) {
            this.comments = comments;
        } else {
            getComments().addAll(comments);
        }
        return this;
    }

    /**
     * @return the empty
     */
    public boolean isEmpty() {
        return empty;
    }

    /**
     * @param empty the empty to set
     */
    public void setEmpty(boolean empty) {
        this.empty = empty;
    }

    /**
     * @return the file
     */
    public File getFile() {
        return file;
    }

    /**
     * @param file the file to set
     */
    public void setFile(File file) {
        this.file = file;
    }

    /**
     * 按名称为key的Message的map对象
     */
    public Map<String, Message> getMessageMap() {
        if (messageMap == null) {
            messageMap = new HashMap<>();
        }
        List<Message> msgs = getMessages();
        if (msgs.size() != messageMap.size()) {
            messageMap.clear();
            for (Message msg : msgs) {
                messageMap.put(msg.getName(), msg);
            }
        }
        return messageMap;
    }
}