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

package io.edap.protobuf.idl.test.dto;

import java.util.List;
import java.util.Map;

public class AllType<T> {

    private byte fieldByte;
    private Byte fieldByteObj;
    private short fieldShort;
    private Short fieldShortObj;
    private int fieldInt;
    private Integer FieldIntObj;
    private long fieldLong;
    private Long fieldLongObj;
    private float fieldFloat;
    private Float fieldFloatObj;
    private double fieldDouble;
    private Double fieldDoubleObj;
    private char fieldChar;
    private Character fieldCharObj;
    private String fieldString;
    private boolean fieldBoolean;
    private Boolean fieldBooleanObj;
    private byte[] fieldArrayByte;
    private Byte[] fieldArrayByteObj;
    private short[] fieldArrayShort;
    private Short[] fieldArrayShortObj;
    private int[] fieldArrayInt;
    private Integer[] FieldArrayIntObj;
    private long[] fieldArrayLong;
    private Long[] fieldArrayLongObj;
    private float[] fieldArrayFloat;
    private Float[] fieldArrayFloatObj;
    private double[] fieldArrayDouble;
    private Double[] fieldArrayDoubleObj;
    private char[] fieldArrayChar;
    private Character[] fieldArrayCharObj;
    private String[] fieldArrayString;
    private boolean[] fieldArrayBoolean;
    private Boolean[] fieldArrayBooleanObj;
    private List<Byte> fieldListByteObj;
    private List<Short> fieldListShortObj;
    private List<Integer> FieldListIntObj;
    private List<Long> fieldListLongObj;
    private List<Float> fieldListFloatObj;
    private List<Double> fieldListDoubleObj;
    private List<Character> fieldListCharObj;
    private List<String> fieldListString;
    private List<Boolean> fieldListBooleanObj;
    private List<T> fieldGernericList;
    private Map<String, Integer> fieldMapStringInt;
    private Order[] fieldArrayMessage;
    private Order fieldMessage;
}
