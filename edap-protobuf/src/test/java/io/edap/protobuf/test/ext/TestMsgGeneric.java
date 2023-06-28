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

package io.edap.protobuf.test.ext;

import io.edap.json.Eson;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.test.message.ext.ComplexModel;
import io.edap.protobuf.test.message.ext.Person;
import io.edap.protobuf.test.message.ext.Point;
import io.edap.protobuf.test.message.v3.Project;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;

/**
 * @author : luysh@yonyou.com
 * @date : 2020/9/28
 */
public class TestMsgGeneric {

    @ParameterizedTest
    @ValueSource(strings = {
            "a"
    })

    void testListObject(String value) throws EncodeException, ProtoBufException {
        ComplexModel<Point> model = new ComplexModel<Point>();
        model.setId(1);
        Person person = new Person();
        person.setName("Tom");
        person.setAge(86);
        person.setBirthDay(new Date());
        person.setSensitiveInformation("This should be private over the wire");
        model.setPerson(person);

        List<Point> points = new ArrayList<Point>();
        Point point = new Point();
        point.setX(3);
        point.setY(4);
        points.add(point);

        point = new Point();
        point.setX(100);
        point.setY(101);
        points.add(point);

        //远程方法调用
        model.setPoints(points);

        byte[] data = ProtoBuf.ser(model);

        for (int i=0;i<6;i++) {
            System.out.println("byte: " + data[i]);
        }

        System.out.println(conver2HexStr(data));

        ComplexModel<Project> model2 = (ComplexModel<Project>)ProtoBuf.der(data);
        System.out.println(Eson.toJsonString(model2));

        data = ProtoBuf.ser(model, ProtoBufWriter.WriteOrder.REVERSE);

        System.out.println(conver2HexStr(data));

        model2 = (ComplexModel<Project>)ProtoBuf.der(data);
        System.out.println(Eson.toJsonString(model2));
    }
}
