/*
 * Copyright 2022 The edap Project
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

package io.edap.protobuf.idl.util;

import io.edap.protobuf.idl.model.*;
import io.edap.protobuf.idl.model.impl.IdlJavaClass;
import io.edap.protobuf.idl.model.impl.IdlParameterizedTypeImpl;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

import java.util.*;

public class ClassVisitorUtil {

    public static ThreadLocal<Set<String>> LOCAL_TYPES =
            new ThreadLocal<Set<String>>() {
                @Override
                protected Set<String> initialValue() {
                    return new HashSet<>();
                }
            };

    private ClassVisitorUtil() {}

    public static IdlMethod parseMethodDesc(MethodNode methodNode) {
        IdlMethod md = new IdlMethod();
        md.setName(methodNode.name);
        String desc = methodNode.desc;
        String sign = methodNode.signature;
        List<MethodParam> params = new ArrayList<>();
        MethodDescParser mdp = new MethodDescParser(desc);
        MethodDesc mdc = mdp.parse();
        List<IdlJavaType> typeClasses = mdc.types;
        List<ParameterNode> paramNames = methodNode.parameters;
        IdlJavaType returnType = null;
        if (!CollectionUtils.isEmpty(typeClasses)) {
            List<IdlJavaType> types = null;
            if (!StringUtil.isEmpty(sign)) {
                MethodDescParser signMdp = new MethodDescParser(sign);
                MethodDesc signMdc = signMdp.parse();
                types = signMdc.getTypes();
                returnType = signMdc.returnsType;
            }
            for (int i=0;i<typeClasses.size();i++) {
                IdlJavaType ijc = typeClasses.get(i);
                MethodParam mp = new MethodParam();
                mp.setIdlJavaClass((IdlJavaClass)ijc);
                if (types != null) {
                    mp.setIdlJavaType(types.get(i));
                }
                LOCAL_TYPES.get().add(ijc.toString());
                if (!CollectionUtils.isEmpty(paramNames)) {
                    mp.setParamName(paramNames.get(i).name);
                }
                mp.setIdlMethod(md);
                params.add(mp);
            }
        }
        md.setMethodParams(params);
        md.setReturns((IdlJavaClass) mdc.getReturnsType());
        md.setReturnType(returnType);
//        if (returnType != null) {
//            LOCAL_TYPES.get().add(returnType.toString());
//        } else {
//            LOCAL_TYPES.get().add(mdc.getReturnsType().toString());
//        }
        LOCAL_TYPES.get().add(mdc.getReturnsType().toString());
        return md;
    }

    public static class ClazzSignature {
        private List<FormalType> formalTypes;
        private IdlJavaType parentType;
        private List<IdlJavaType> ifaces;

        public List<FormalType> getFormalTypes() {
            return formalTypes;
        }

        public void setFormalTypes(List<FormalType> formalTypes) {
            this.formalTypes = formalTypes;
        }

        public IdlJavaType getParentType() {
            return parentType;
        }

        public void setParentType(IdlJavaType parentType) {
            this.parentType = parentType;
        }

        public List<IdlJavaType> getIfaces() {
            return ifaces;
        }

        public void setIfaces(List<IdlJavaType> ifaces) {
            this.ifaces = ifaces;
        }
    }

    public static class SignatureParser {

        private String signature;
        private int pos;
        public SignatureParser(String signature) {
            pos = 0;
            this.signature = signature;
        }

        public ClazzSignature parse() {
            char c = signature.charAt(pos);
            ClazzSignature clazzSignature = new ClazzSignature();
            if (c == '<') {
                List<FormalType> formalTypes = new ArrayList<>();
                pos++;
                int index = signature.indexOf(':', pos);
                FormalType ft = new FormalType();
                ft.setFormal(signature.substring(pos, index));
                pos = index + 1;
                IdlJavaType idlJavaType = parseType();
                ft.setType(idlJavaType);
                formalTypes.add(ft);
                c = signature.charAt(pos);
                while (c != '>') {
                    index = signature.indexOf(':', pos);
                    ft = new FormalType();
                    ft.setFormal(signature.substring(pos, index));
                    pos = index + 1;
                    idlJavaType = parseType();
                    ft.setType(idlJavaType);
                    formalTypes.add(ft);
                    c = signature.charAt(pos);
                }
                pos++;
                clazzSignature.setFormalTypes(formalTypes);
            }
            IdlJavaType parentType = parseType();
            clazzSignature.setParentType(parentType);
            List<IdlJavaType> ifaces = new ArrayList<>();
            while (pos < signature.length() - 1) {
                IdlJavaType iface = parseType();
                ifaces.add(iface);
            }
            clazzSignature.setIfaces(ifaces);
            return clazzSignature;
        }

        private IdlJavaType parseType() {
            IdlJavaType type = null;
            if (pos < signature.length()) {
                char c = signature.charAt(pos++);
                switch (c) {
                    case 'B':
                        type = new IdlJavaClass("B", "byte");
                        break;
                    case 'S':
                        type = new IdlJavaClass("S", "short");
                        break;
                    case 'F':
                        type = new IdlJavaClass("F", "float");
                        break;
                    case 'D':
                        type = new IdlJavaClass("D", "double");
                        break;
                    case 'Z':
                        type = new IdlJavaClass("Z", "boolean");
                        break;
                    case 'I':
                        type = new IdlJavaClass("I", "int");
                        break;
                    case 'J':
                        type = new IdlJavaClass("J", "long");
                        break;
                    case 'C':
                        type = new IdlJavaClass("C", "char");
                        break;
                    case 'L':
                        pos--;
                        type = parseObjectType();
                        break;
                    case 'V':
                        type = new IdlJavaClass("V", "void");
                        break;
                    default:
                        throw new RuntimeException("desc is error format desc:\"" + signature + "[" + pos + "] is " + c + "\"");
                }
            }
            return type;
        }

        private IdlJavaType parseObjectType() {
            IdlJavaType type = null;
            boolean isGeneric = false;
            StringBuilder binaryName = new StringBuilder();
            binaryName.append(signature.charAt(pos++));
            StringBuilder canonicalName = new StringBuilder();
            List<IdlJavaType> actualTypeArgs = new ArrayList<>();
            while (pos < signature.length()) {
                char c = signature.charAt(pos++);
                if (c == ';') {
                    binaryName.append(';');
                    String rawTypeStr = binaryName.toString();
                    String canonicalNameStr = canonicalName.toString();
                    if (isGeneric) {
                        IdlJavaClass rawType = new IdlJavaClass(rawTypeStr, canonicalNameStr);
                        type = new IdlParameterizedTypeImpl(rawType, actualTypeArgs.toArray(new IdlJavaType[]{}));
                    } else {
                        type = new IdlJavaClass(rawTypeStr, canonicalNameStr);
                    }
                    return type;
                } else if (c == '<') {
                    isGeneric = true;
                    char typeChar = signature.charAt(pos);
                    if (typeChar == '*') {
                        IdlJavaType argType = new IdlJavaClass("*", "*");
                        pos += 2;
                        actualTypeArgs.add(argType);
                    } else {
                        //pos++;
                        actualTypeArgs.add(parseObjectType());
                        if (pos >= signature.length() - 1) {
                            break;
                        }
                        char nextc = signature.charAt(pos);
                        while (nextc != '>') {
                            //pos++;
                            actualTypeArgs.add(parseObjectType());
                            if (pos >= signature.length() - 1) {
                                break;
                            }
                            nextc = signature.charAt(pos);
                        }
                    }
                } else if (c == '>') {

                } else {
                    binaryName.append(c);
                    if (c == '/') {
                        canonicalName.append('.');
                    } else {
                        canonicalName.append(c);
                    }
                }
            }
            return type;
        }

    }

    public static class FieldDesc {
        private IdlJavaClass clazz;
        private IdlJavaType  type;

        public IdlJavaClass getClazz() {
            return clazz;
        }

        public void setClazz(IdlJavaClass clazz) {
            this.clazz = clazz;
        }

        public IdlJavaType getType() {
            return type;
        }

        public void setType(IdlJavaType type) {
            this.type = type;
        }
    }

    public static class MethodDesc {
        private List<IdlJavaType> types;
        private IdlJavaClass returns;
        private IdlJavaType returnsType;

        public List<IdlJavaType> getTypes() {
            return types;
        }

        public void setTypes(List<IdlJavaType> types) {
            this.types = types;
        }

        public IdlJavaClass getReturns() {
            return returns;
        }

        public void setReturns(IdlJavaClass returns) {
            this.returns = returns;
        }

        public IdlJavaType getReturnsType() {
            return returnsType;
        }

        public void setReturnsType(IdlJavaType returnsType) {
            this.returnsType = returnsType;
        }
    }

    public static class FieldDescParser {
        private int pos;
        private String desc;
        public FieldDescParser(String desc) {
            this.desc = desc;
        }

        public FieldDesc parse() {
            FieldDesc fieldDesc = new FieldDesc();
            while (pos < desc.length()) {
                char c = desc.charAt(pos);
                if (c == '[') { // 数组
                    pos++;
                    IdlJavaType arrType = parseType();
                    if (arrType instanceof IdlJavaClass) {
                        IdlJavaClass arrClazz = (IdlJavaClass)arrType;
                        IdlJavaClass ijc = new IdlJavaClass("[" + arrClazz.binaryName(),
                                "[" + arrClazz.canonicalName());
                        fieldDesc.clazz = ijc;
                        fieldDesc.type = ijc;
                    }
                } else if (c == ';') {
                    break;
                } else {
                    IdlJavaType type = parseType();
                    if (type instanceof IdlParameterizedType) {
                        fieldDesc.clazz = (IdlJavaClass) ((IdlParameterizedType)type).rawType();
                    } else {
                        fieldDesc.clazz = (IdlJavaClass)type;
                    }
                    fieldDesc.type = type;
                }
            }
            return fieldDesc;
        }

        private IdlJavaType parseType() {
            IdlJavaType type = null;
            if (pos < desc.length()) {
                char c = desc.charAt(pos++);
                switch (c) {
                    case 'B':
                        type = new IdlJavaClass("B", "byte");
                        break;
                    case 'S':
                        type = new IdlJavaClass("S", "short");
                        break;
                    case 'F':
                        type = new IdlJavaClass("F", "float");
                        break;
                    case 'D':
                        type = new IdlJavaClass("D", "double");
                        break;
                    case 'Z':
                        type = new IdlJavaClass("Z", "boolean");
                        break;
                    case 'I':
                        type = new IdlJavaClass("I", "int");
                        break;
                    case 'J':
                        type = new IdlJavaClass("J", "long");
                        break;
                    case 'C':
                        type = new IdlJavaClass("C", "char");
                        break;
                    case 'L':
                        type = parseObjectType();
                        break;
                    case 'V':
                        type = new IdlJavaClass("V", "void");
                        break;
                    default:
                        throw new RuntimeException("desc is error format desc:\"" + desc + "[" + pos + "] is " + c + "\"");
                }
            }
            return type;
        }

        private IdlJavaType parseObjectType() {
            IdlJavaType type = null;
            boolean isGeneric = false;
            StringBuilder binaryName = new StringBuilder();
            binaryName.append('L');
            StringBuilder canonicalName = new StringBuilder();
            List<IdlJavaType> actualTypeArgs = new ArrayList<>();
            while (pos < desc.length()) {
                char c = desc.charAt(pos++);
                if (c == ';') {
                    binaryName.append(';');
                    String rawTypeStr = binaryName.toString();
                    String canonicalNameStr = canonicalName.toString();
                    if (isGeneric) {
                        IdlJavaClass rawType = new IdlJavaClass(rawTypeStr, canonicalNameStr);
                        type = new IdlParameterizedTypeImpl(rawType, actualTypeArgs.toArray(new IdlJavaType[]{}));
                    } else {
                        type = new IdlJavaClass(rawTypeStr, canonicalNameStr);
                    }
                    return type;
                } else if (c == '<') {
                    isGeneric = true;
                    char typeChar = desc.charAt(pos);
                    if (typeChar == '*') {
                        IdlJavaType argType = new IdlJavaClass("*", "*");
                        pos += 2;
                        actualTypeArgs.add(argType);
                    } else {
                        pos++;
                        actualTypeArgs.add(parseObjectType());
                        if (pos >= desc.length() - 1) {
                            break;
                        }
                        char nextc = desc.charAt(pos);
                        while (nextc != '>') {
                            pos++;
                            actualTypeArgs.add(parseObjectType());
                            if (pos >= desc.length() - 1) {
                                break;
                            }
                            nextc = desc.charAt(pos);
                        }
                    }
                } else if (c == '>') {

                } else {
                    binaryName.append(c);
                    if (c == '/') {
                        canonicalName.append('.');
                    } else {
                        canonicalName.append(c);
                    }
                }
            }
            return type;
        }
    }

    public static class MethodDescParser {

        private int pos;
        private String desc;
        public MethodDescParser(String desc) {
            this.desc = desc;
        }

        public MethodDesc parse() {
            MethodDesc methodDesc = new MethodDesc();
            List<IdlJavaType> types = new ArrayList<>();
            char token = desc.charAt(pos++);
            if (token != '(') {
                throw new RuntimeException("not method desc start char is not '('");
            }
            while (pos < desc.length()) {
                char c = desc.charAt(pos);
                if (c == '[') { // 数组
                    pos++;
                    IdlJavaType arrType = parseType();
                    if (arrType instanceof IdlJavaClass) {
                        IdlJavaClass arrClazz = (IdlJavaClass)arrType;
                        IdlJavaClass ijc = new IdlJavaClass("[L" + arrClazz.binaryName() + ";",
                                "[" + arrClazz.canonicalName());
                        types.add(ijc);
                    }
                } else if (c == ')') {
                    methodDesc.setTypes(types);
                    pos++;
                    methodDesc.setReturnsType(parseType());
                } else {
                    types.add(parseType());
                }
            }
            return methodDesc;
        }

        private IdlJavaType parseType() {
            IdlJavaType type = null;
            if (pos < desc.length()) {
                char c = desc.charAt(pos++);
                switch (c) {
                    case 'B':
                        type = new IdlJavaClass("B", "byte");
                        break;
                    case 'S':
                        type = new IdlJavaClass("S", "short");
                        break;
                    case 'F':
                        type = new IdlJavaClass("F", "float");
                        break;
                    case 'D':
                        type = new IdlJavaClass("D", "double");
                        break;
                    case 'Z':
                        type = new IdlJavaClass("Z", "boolean");
                        break;
                    case 'I':
                        type = new IdlJavaClass("I", "int");
                        break;
                    case 'J':
                        type = new IdlJavaClass("J", "long");
                        break;
                    case 'C':
                        type = new IdlJavaClass("C", "char");
                        break;
                    case 'L':
                        type = parseObjectType();
                        break;
                    case 'V':
                        type = new IdlJavaClass("V", "void");
                        break;
                    default:
                        throw new RuntimeException("desc is error format desc:\"" + desc + "[" + pos + "] is " + c + "\"");
                }
            }
            return type;
        }

        private IdlJavaType parseObjectType() {
            IdlJavaType type = null;
            boolean isGeneric = false;
            StringBuilder binaryName = new StringBuilder();
            binaryName.append('L');
            StringBuilder canonicalName = new StringBuilder();
            List<IdlJavaType> actualTypeArgs = new ArrayList<>();
            while (pos < desc.length()) {
                char c = desc.charAt(pos++);
                if (c == ';') {
                    binaryName.append(';');
                    String rawTypeStr = binaryName.toString();
                    String canonicalNameStr = canonicalName.toString();
                    if (isGeneric) {
                        IdlJavaClass rawType = new IdlJavaClass(rawTypeStr, canonicalNameStr);
                        type = new IdlParameterizedTypeImpl(rawType, actualTypeArgs.toArray(new IdlJavaType[]{}));
                    } else {
                        type = new IdlJavaClass(rawTypeStr, canonicalNameStr);
                    }
                    return type;
                } else if (c == '<') {
                    isGeneric = true;
                    pos++;
                    actualTypeArgs.add(parseObjectType());
                    if (pos >= desc.length() - 1) {
                        break;
                    }
                    char nextc = desc.charAt(pos);
                    while (nextc != '>') {
                        pos++;
                        actualTypeArgs.add(parseObjectType());
                        if (pos >= desc.length() - 1) {
                            break;
                        }
                        nextc = desc.charAt(pos);
                    }
                } else if (c == '>') {

                } else {
                    binaryName.append(c);
                    if (c == '/') {
                        canonicalName.append('.');
                    } else {
                        canonicalName.append(c);
                    }
                }
            }
            return type;
        }
    }

}
