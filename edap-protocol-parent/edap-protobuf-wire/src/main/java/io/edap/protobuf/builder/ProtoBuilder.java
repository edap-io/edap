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

package io.edap.protobuf.builder;

import io.edap.protobuf.internal.CodeBuilder;
import io.edap.protobuf.wire.*;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.TagReserved.StartEnd;

import java.util.*;

import static io.edap.protobuf.wire.Service.ServiceType.*;

/**
 * 根据protoFile的对象构建proto文件的基础类
 * @author louis
 */
public abstract class ProtoBuilder {

    protected Proto proto;
    public static final String EMPTY = "";
    public static final String LN    = "\n";
    public static final String LN2   = "\n\n";
    protected List<String> optionNames;
    /**
     * 是否使用紧凑格式来减少生成proto文件的大小,紧凑模式缩进使用tab键，对于proto文件不做严格的对齐
     */
    private boolean compactIdentation;

    public ProtoBuilder(Proto proto) {
        this.proto = proto;
        this.compactIdentation = true;
        optionNames = new ArrayList<>();
        optionNames.add("java_package");
        optionNames.add("java_multiple_files");
        optionNames.add("java_outer_classname");
        optionNames.add("optimize_for");
        optionNames.add("cc_enable_arenas");
        optionNames.add("objc_class_prefix");
        optionNames.add("go_package");
        optionNames.add("csharp_namespace");
    }

    protected String syntax() {
        return Syntax.PROTO_2.getValue();
    }

    protected String protoPackage() {
        return proto.getProtoPackage();
    }

    protected String impString() {
        if (proto.getImports() == null || proto.getImports().isEmpty()) {
            return EMPTY;
        }
        StringBuilder imps = new StringBuilder();
        proto.getImports().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(e -> {
                    if (e.charAt(0) != '\'' && e.charAt(0) != '"') {
                        imps.append("import \"").append(e).append("\";").append(LN);
                    } else {
                        imps.append("import ").append(e).append(";").append(LN);
                    }
                });
        return imps.toString();
    }

    protected String options() {
        List<Option> options = proto.getOptions();
        if (options == null || options.isEmpty()) {
            return EMPTY;
        }
        CodeBuilder ops = new CodeBuilder();
        ops.setCompactIdentation(compactIdentation);
        int len = 0;
        for (int i=0;i<options.size();i++) {
            Option option = proto.getOptions().get(i);
            if (option.getName() == null || option.getValue() == null
                    || option.getName().trim().length() == 0 || option.getValue().trim().length() == 0) {
                continue;
            }
            if (option.getName().length() > len) {
                len = option.getName().length();
            }
        }
        for (int i=0;i<options.size();i++) {
            Option option = proto.getOptions().get(i);
            if (option.getName() == null || option.getValue() == null
                    || option.getName().trim().length() == 0 || option.getValue().trim().length() == 0) {
                continue;
            }
            String space = fillSpace(len - options.get(i).getName().length());
            ops.e("option $name$$spaces$ = $value$;").arg(option.getName(), space, option.getValue()).ln();
        }
        return ops.toString();
    }

    public String fillSpace(int count) {
        if (compactIdentation) {
            return EMPTY;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append(" ");
            }
            return sb.toString();
        }
    }

    public String message(Message msg, final int depth) {
        if (msg == null) {
            return EMPTY;
        }
        int level;
        if (depth < 1) {
            level = 1;
        } else {
            level = depth;
        }
        CodeBuilder cb = new CodeBuilder();
        cb.setCompactIdentation(compactIdentation);
        cb.t(level-1).e("message $name$ {").arg(msg.getName()).ln();
        List<Field> fields = msg.getFields();
        appendFields(cb, fields, level);

        List<Oneof> oneofs = msg.getOneofs();
        if (oneofs != null && !oneofs.isEmpty()) {
            oneofs.forEach(o -> appendOneof(cb, o, level + 1));
        }

        List<Extensions> exts = msg.getExtensionses();
        appendExtensionses(cb, exts, level + 1);

        List<Reserved> reserveds = msg.getReserveds();
        if (reserveds != null && !reserveds.isEmpty()) {
            reserveds.forEach(r -> appendReserved(cb, r, level));
        }

        List<Message> childMsgs = msg.getMessages();
        appendMessages(cb, childMsgs, level + 1);

        List<ProtoEnum> protoEnums = msg.getEnums();
        appendEnums(cb, protoEnums, level + 1);

        cb.t(level-1).c("}").ln();
        return cb.toString();
    }

    private void appendFields(CodeBuilder cb, List<Field> fields, int level) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        int typeLen = 0;
        int nameLen = 0;
        int cardinalityLen = 0;
        for (Field f : fields) {
            if (f.getTypeString().length() > typeLen) {
                typeLen = f.getTypeString().length();
            }
            if (f.getName().length() > nameLen) {
                nameLen = f.getName().length();
            }
            if (syntax() == Syntax.PROTO_3.getValue()) {
                if (f.getCardinality() == Cardinality.REPEATED && f.getCardinality().getValue().length() > cardinalityLen) {
                    cardinalityLen = f.getCardinality().getValue().length();
                }
            }
        }
        int typeLen0 = typeLen;
        int nameLen0 = nameLen;
        int cardinalityLen0 = cardinalityLen + 1;
        fields.stream()
                .sorted(Comparator.comparingInt(Field::getTag))
                .forEach(f -> appendField(cb, f, typeLen0, nameLen0, cardinalityLen0, level));
    }

    private void appendExtensionses(CodeBuilder cb, List<Extensions> exts, int depth) {
        if (exts == null || exts.isEmpty()) {
            return;
        }
        exts.stream()
                .sorted(Comparator.comparingInt(Extensions::getStartTag))
                .forEach(e -> {
                    String end;
                    if (e.getEndTag() == WireFormat.MAX_TAG_VALUE) {
                        end = "max";
                    } else {
                        end = String.valueOf(e.getStartTag());
                    }
                    String[] args = new String[2];
                    args[0] = String.valueOf(e.getStartTag());
                    args[1] = end;
                    cb.t(depth).e("extensions $start$ to $end$;").arg(args).ln();
                });
    }

    private void appendMessages(CodeBuilder cb, List<Message> msgs, int depth) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        msgs.stream()
                .sorted(Comparator.comparing(Message::getName))
                .forEach(e -> cb.c(message(e, depth)).ln());
    }

    private void appendEnums(CodeBuilder cb, List<ProtoEnum> protoEnums, int depth) {
        if (protoEnums == null || protoEnums.isEmpty()) {
            return;
        }
        protoEnums.stream()
                .sorted(Comparator.comparing(ProtoEnum::getName))
                .forEach(e -> cb.c(protoEnum(e, depth)).ln());
    }

    private void appendOneof(CodeBuilder cb, Oneof oneof, int indentCount) {
        cb.t(indentCount).e("oneof $name$ {").arg(oneof.getName()).ln();
        List<Field> fields = oneof.getFields();
        if (fields != null && !fields.isEmpty()) {
            Optional<Field> maxType = fields.stream()
                    .max(Comparator.comparingInt((Field f) -> f.getTypeString().length()));
            int typeLen = maxType.isPresent()?maxType.get().getTypeString().length():1;
            fields.stream()
                    .sorted(Comparator.comparingInt(Field::getTag))
                    .forEach(f -> appendField(cb, f, typeLen, 1, 1, indentCount + 1));
        }
        cb.t(indentCount).c("}").ln();
    }

    public void appendField(CodeBuilder cb, Field field, int typeLen, int nameLen, int cardinalityLen, int indentCount) {
        String cardinality = fieldCardinality(field.getCardinality());
        cb.t(indentCount);
        if (cardinalityLen > 1) {
            if (cardinality != null && !cardinality.isEmpty()) {
                cb.c(cardinality).c(" ");
            } else {
                if (!compactIdentation) {
                    cb.c("         ");
                }
            }
        }
        cb.c(field.getType()).c(" ");
        String spaces1 = fillSpace(typeLen - field.getTypeString().length());
        String spaces2 = fillSpace(nameLen - field.getName().length());
        String[] args = new String[5];
        args[0] = spaces1;
        args[1] = field.getName();
        args[2] = spaces2;
        args[3] = String.valueOf(field.getTag());
        StringBuilder options = new StringBuilder();
        if (field.getOptions() != null && field.getOptions().size() > 0) {
            options.append(" [");
            for (Option option : field.getOptions()) {
                if (options.length() > 2) {
                    options.append(',');
                }
                options.append(option.getName()).append('=').append(option.getValue());
            }
            options.append(']');
        }
        args[4] = options.toString();
        cb.e("$spaces$$name$$spaces2$ = $tag$$options$;").arg(args).ln();
    }

    public void appendReserved(CodeBuilder cb, Reserved reserved, int indent) {
        if (reserved == null) {
            return;
        }

        if (reserved instanceof TagReserved) {
            appendTagReserved(cb, (TagReserved)reserved, indent);
        } else if (reserved instanceof NameReserved) {
            appendNameReserved(cb, (NameReserved)reserved, indent);
        }
    }

    private void appendNameReserved(CodeBuilder cb, NameReserved reserved, int indent) {
        if (reserved == null || reserved.getFieldNames() == null
                || reserved.getFieldNames().isEmpty()) {
            return;
        }
        cb.t(indent).c("reserved ");
        StringBuilder res = new StringBuilder();
        reserved.getFieldNames().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(n -> {
                    if (res.length() > 0) {
                        res.append(", ");
                    }
                    res.append("\"").append(n).append("\"");
                });
        res.append(";").append(LN);
        cb.c(res.toString());
    }

    private void appendTagReserved(CodeBuilder cb, TagReserved reserved, int indent) {
        if (reserved == null || (reserved.getStartEnds() == null
                && reserved.getTags() == null)) {
            return;
        }
        StringBuilder res = new StringBuilder();
        List<Integer> tags = reserved.getTags();
        List<StartEnd> ses = reserved.getStartEnds();

        cb.t(indent).c("reserved ");
        if (tags != null) {
            tags.stream().sorted(Comparator.naturalOrder())
                    .forEach(r -> {
                        if (res.length() > 0) {
                            res.append(", ");
                        }
                        res.append(r);
                    });
        }

        appendStartEnd(cb, ses);
        if (res.length() > 0) {
            res.append(";").append(LN);
        }
        cb.c(res.toString());
    }

    private void appendStartEnd(CodeBuilder cb, List<StartEnd> startEnds) {
        if (startEnds == null || startEnds.isEmpty()) {
            return;
        }
        startEnds.stream().sorted(Comparator.comparingInt(StartEnd::getStartTag))
                .forEach(r -> {
                    String end;
                    if (r.getEndTag() == WireFormat.MAX_TAG_VALUE) {
                        end = "max";
                    } else {
                        end = String.valueOf(r.getStartTag());
                    }
                    String sep = ",";
                    cb.e("$sep$ $start$ to $end$").arg(sep, String.valueOf(r.getStartTag()),
                            end);
                });
    }

    public String fieldCardinality(Cardinality cardinality) {
        if (cardinality == null) {
            return EMPTY;
        }
        return cardinality.getValue();
    }

    public String protoEnum(ProtoEnum protoEnum, int depth) {
        if (protoEnum == null) {
            return EMPTY;
        }
        int level;
        if (depth < 1) {
            level = 1;
        } else {
            level = depth;
        }
        CodeBuilder cb = new CodeBuilder();
        cb.setCompactIdentation(compactIdentation);
        cb.t(depth-1).e("enum $name$ {").arg(protoEnum.getName()).ln();
        List<ProtoEnum.EnumEntry> entries = protoEnum.getEntries();
        if (entries != null && !entries.isEmpty()) {
            Optional<ProtoEnum.EnumEntry> maxName = entries.stream()
                    .max(Comparator.comparingInt((ProtoEnum.EnumEntry f) -> f.getLabel().length()));
            int nameLen = maxName.isPresent()?maxName.get().getLabel().length():1;
            entries.stream()
                    .sorted(Comparator.comparingInt(ProtoEnum.EnumEntry::getValue))
                    .forEach(e -> {
                        String[] args = new String[3];
                        args[0] = e.getLabel();
                        args[1] = fillSpace(nameLen - e.getLabel().length());
                        args[2] = String.valueOf(e.getValue());
                        cb.t(level).e("$label$$space$ = $value$;").arg(args).ln();
                    });
        }
        cb.t(depth-1).e("}").ln();
        return cb.toString();
    }

    private String service(Service service, int level) {
        CodeBuilder cb = new CodeBuilder();
        cb.setCompactIdentation(compactIdentation);
        if (service == null) {
            return cb.toString();
        }
        Comment serviceComment = service.getComments();
        if (serviceComment != null && serviceComment.getLines() != null && !serviceComment.getLines().isEmpty()) {
            if (serviceComment.getType() == Comment.CommentType.DOCUMENT) {
                cb.t(level - 1).c("/**").ln();
                for (String line : serviceComment.getLines()) {
                    cb.t(level - 1).e(" * $comment$").arg(line).ln();
                }
                cb.t(level - 1).c(" */").ln();
            } else if (serviceComment.getType() == Comment.CommentType.MULTILINE) {
                cb.t(level - 1).c("/*").ln();
                for (String line : serviceComment.getLines()) {
                    cb.t(level - 1).e(" * $comment$").arg(line).ln();
                }
                cb.t(level - 1).c(" */").ln();
            } else {
                for (String line : serviceComment.getLines()) {
                    cb.t(level - 1).e("// $comment$").arg(line).ln();
                }
            }
        }
        cb.t(level-1).e("service $serviceName$ {").arg(service.getName()).ln();
        if (service.getMethods() != null && !service.getMethods().isEmpty()) {
            service.getMethods().forEach(m -> {
                if (m.getComment() != null) {
                    Comment comment = m.getComment();
                    if (comment.getType() == Comment.CommentType.DOCUMENT) {
                        cb.t(level).c("/**").ln();
                        for (String line : comment.getLines()) {
                            cb.t(level).e(" * $comment$").arg(line).ln();
                        }
                        cb.t(level).c(" */").ln();
                    } else if (comment.getType() == Comment.CommentType.MULTILINE) {
                        cb.t(level).c("/*").ln();
                        for (String line : comment.getLines()) {
                            cb.t(level).e(" * $comment$").arg(line).ln();
                        }
                        cb.t(level).c(" */").ln();
                    } else {
                        for (String line : comment.getLines()) {
                            cb.t(level).e("// $comment$").arg(line).ln();
                        }
                    }
                }
                String request = (m.getType()==BIDIRECTIONAL||m.getType() == CLIENT_STREAM?"stream ":"") + m.getRequest();
                String response = (m.getType()==BIDIRECTIONAL||m.getType() == SERVER_STREAM?"stream ":"") + m.getResponse();
                cb.t(level).e("rpc $methodName$($request$) returns ($response$);")
                        .arg(m.getName(), request, response).ln();
            });
        }
        cb.t(level-1).c("}").ln();
        return cb.toString();
    }

    public String toProtoString() {
        StringBuilder sb = new StringBuilder();
        String syntax = syntax();
        if (syntax != null && !syntax.isEmpty()) {
            sb.append("syntax = \"").append(syntax).append("\";").append(LN2);
        }
        String pack = protoPackage();
        if (pack != null && !pack.isEmpty()) {
            sb.append("package ").append(pack).append(";\n\n");
        }
        String imps = impString();
        if (imps != null && !imps.isEmpty()) {
            sb.append(imps).append(LN);
        }
        String ops = options();
        if (ops != null && !ops.isEmpty()) {
            sb.append(ops).append(LN);
        }

        List<Service> services = proto.getServices();
        if (services != null && !services.isEmpty()) {
            services.forEach(s -> sb.append(service(s, 1)).append(LN));
        }

        List<Message> msgs = proto.getMessages();
        if (msgs != null && !msgs.isEmpty()) {
            msgs.stream()
                    .sorted(Comparator.comparing(Message::getName))
                    .forEach(e -> sb.append(message(e, 1)).append(LN));
        }
        List<ProtoEnum> protoEnums = proto.getEnums();
        if (protoEnums != null && !protoEnums.isEmpty()) {
            protoEnums.stream()
                    .sorted(Comparator.comparing(ProtoEnum::getName))
                    .forEach(e -> sb.append(protoEnum(e, 1)).append(LN));
        }
        return sb.toString();
    }

    /**
     * 是否使用紧凑格式来减少生成proto文件的大小,紧凑模式缩进使用tab键，对于proto文件不做严格的对齐
     */
    public boolean isCompactIdentation() {
        return compactIdentation;
    }

    public void setCompactIdentation(boolean compactIdentation) {
        this.compactIdentation = compactIdentation;
    }
}