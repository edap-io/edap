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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

    public ProtoBuilder(Proto proto) {
        this.proto = proto;
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
        if (optionNames == null || optionNames.isEmpty()) {
            return EMPTY;
        }
        CodeBuilder ops = new CodeBuilder();
        Optional<String> maxlen = optionNames.stream()
                .max(Comparator.comparingInt(String::length));
        int len = maxlen.isPresent()?maxlen.get().length():1;
        optionNames.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(e -> {
                    String v = getOptionValue(e);
                    if (v != null) {
                        String[] args = new String[3];
                        args[0] = e;
                        args[1] = fillSpace(len - e.length());
                        args[2] = v;
                        ops.e("option $name$$spaces$ = $value$;").arg(args).ln();
                    }
                });
        return ops.toString();
    }

    public static String fillSpace(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<count;i++) {
            sb.append(" ");
        }
        return sb.toString();
    }

    private String getOptionValue(String name) {
        if (name == null || name.trim().length() < 1 || proto == null ||
                proto.getOptions() == null || proto.getOptions().isEmpty()) {
            return null;
        }
        String value = null;
        for (int i=0;i<proto.getOptions().size();i++) {
            Option option = proto.getOptions().get(i);
            if (option.getName().equals(name)) {
                value = option.getValue();
                break;
            }
        }
        return value;
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
        Optional<Field> maxType = fields.stream()
                .max(Comparator.comparingInt(f -> f.getTypeString().length()));
        int typeLen = maxType.isPresent()?maxType.get().getTypeString().length():1;
        fields.stream()
                .sorted(Comparator.comparingInt(Field::getTag))
                .forEach(f -> appendField(cb, f, typeLen, level));
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
                    .forEach(f -> appendField(cb, f, typeLen, indentCount + 1));
        }
        cb.t(indentCount).c("}").ln();
    }

    public void appendField(CodeBuilder cb, Field field, int typeLen, int indentCount) {
        String cardinality = fieldCardinality(field.getCardinality());
        if (cardinality != null && !cardinality.isEmpty()) {
            cb.t(indentCount).c(cardinality);
        }
        cb.c(field.getTypeString());
        String spaces1 = fillSpace(typeLen - field.getTypeString().length());
        String[] args = new String[3];
        args[0] = field.getName();
        args[1] = spaces1;
        args[2] = String.valueOf(field.getTag());
        cb.e(" $name$$spaces$ = $tag$;").arg(args).ln();
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
}